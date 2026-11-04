package dev.shiko.batterymonitor.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.shiko.batterymonitor.model.BatterySnapshot
import dev.shiko.batterymonitor.model.ChartSample
import dev.shiko.batterymonitor.model.PowerSummary
import dev.shiko.batterymonitor.model.PowerUiState
import dev.shiko.batterymonitor.model.SessionRecord
import dev.shiko.batterymonitor.model.UsageScenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The single source of truth for the "耗电统计" screen.
 *
 * The whole screen is **live at 1 Hz**: one coroutine heartbeat emits a new [PowerUiState] every
 * second, so `已使用`, `平均功耗`, `理论续航`, the battery telemetry and the chart's right edge all
 * advance continuously. The expensive inputs run in their own, never overlapping, pipelines:
 *
 *  ```
 *  tick (3 s, main) ──► chart point (de-duplicated) ──► state
 *      │
 *      ├─ requestBatterySnapshot()  single-flight: `dumpsys battery` (level/voltage/temp/counter)
 *      └─ stats pipeline            serial loop: `dumpsys batterystats --charged`
 *                                   (per-UID mAh, discharge/time) and, hourly, `--history`
 *  ```
 *
 * The app is **Shizuku-only**: every number comes from the system's own accounting through the
 * shell service. Without the Shizuku permission there is no data — `snapshot` stays `null`, the
 * summary stays zeroed and the UI shows its "grant permission" state.
 *
 * Threading: the repository scope is `Dispatchers.Main.immediate`; all session state is mutated
 * there, so there is no lock. Every blocking framework call (exec, PackageManager) is pushed to
 * `Dispatchers.IO`. A slow `dumpsys` never blocks the tick — the state simply re-emits with the
 * previous numbers.
 */
class PowerStatsRepository(context: Context) {

    private val appContext: Context = context.applicationContext

    private val sampler = BatterySampler(appContext)
    private val store = SessionStore(appContext)
    private val packageVisuals = PackageVisuals(appContext)

    /** Repository lifetime scope; never cancelled so monitoring can be restarted. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var sessionHistory: List<SessionRecord> = store.loadHistory()

    private val _state = MutableStateFlow(
        PowerUiState(
            chartWindowMillis = PowerMath.CHART_WINDOW_MILLIS,
            sessionHistory = sessionHistory,
        ),
    )

    val state: StateFlow<PowerUiState> = _state.asStateFlow()

    // --- session (may be longer than the chart window) -------------------------------------------
    private var sessionStartMillis: Long? = null
    private var startLevelPercent: Int? = null

    /** `Σ power(W) × Δt(ms)` and `Σ Δt(ms)`, folded in on every 1 s tick. */
    private var weightedPowerSum: Double = 0.0
    private var weightSum: Double = 0.0
    private var lastAccumulatorAtMillis: Long = 0L

    // --- live sampling ---------------------------------------------------------------------------
    private var lastSnapshot: BatterySnapshot? = null
    private val windowSamples = mutableListOf<PowerSample>()
    private var chartSamples: List<ChartSample> = emptyList()

    /** Chart points reconstructed from `dumpsys batterystats --history` (Shizuku only). */
    private var historyChartSamples: List<ChartSample> = emptyList()
    private var historySeedAllowed: Boolean = true

    /** Timestamp of the previous tick; drives the `MissedRecord*` gap detection. */
    private var lastTickAtMillis: Long = 0L
    private var lastValidPowerWatts: Float? = null
    private var latestPowerWatts: Float? = null
    private val recentPowers = ArrayDeque<Float>()
    private var lastSamplesPersistedAtMillis: Long = 0L

    /** Fuel-gauge anchor for the energy-delta estimate (charge counter, 1 mAh resolution). */
    private var energyAnchorWattHours: Float? = null
    private var energyAnchorMillis: Long = 0L

    /** Anchor for the Shizuku charge-counter delta (the counter moves once every few seconds). */
    private var counterAnchorMicroAmpHours: Long? = null
    private var counterAnchorMillis: Long = 0L

    // --- Shizuku (system accounting) ---------------------------------------------------------------
    private var shizukuAvailable: Boolean = false
    private var shizukuGranted: Boolean = false

    /** The permission dialog is shown at most once per process, not in a loop. */
    private var shizukuPermissionRequested: Boolean = false
    private var lastBatteryDump: SystemBatteryStatsSource.BatteryDump? = null
    private var lastBatteryDumpMillis: Long = 0L
    private var lastPowerDump: SystemBatteryStatsSource.PowerDump? = null

    /** Per-package foreground intervals reconstructed from the batterystats history stream. */
    private var foregroundIntervals: Map<String, List<TimeInterval>> = emptyMap()
    private var foregroundIntervalsFetchedAtMillis: Long = 0L
    private var lastHistoryFetchMillis: Long = 0L

    /** `Time on battery` from the last stats fetch plus the drift since that fetch. */
    private var systemTimeOnBatteryMillis: Long = 0L
    private var systemStatsFetchedAtMillis: Long = 0L
    private var smoothedSystemAverageWatts: Float? = null
    private var designCapacityMilliAmpHours: Int? = null
    /** Health-adjusted capacity the system learned for this pack (`Last learned battery capacity`). */
    private var learnedCapacityMilliAmpHours: Int? = null

    /**
     * Full-pack watt-hours, computed **once** from the first successful system dump. It is a
     * hardware property (design capacity after health degradation), not a live value, so it must
     * not flicker with every refresh.
     */
    private var fullCapacityWattHoursCache: Float? = null
    private var shizukuPowerWatts: Float? = null

    // --- usage-scenario cache ---------------------------------------------------------------------
    private var scenarios: List<UsageScenario> = emptyList()
    private var scenarioCandidates: List<ScenarioCandidate> = emptyList()
    private var visualCache: Map<String, PackageVisuals.AppVisual> = emptyMap()
    private var lastPermissionCheckMillis: Long = 0L

    // --- lifecycle --------------------------------------------------------------------------------
    private var monitoring: Boolean = false
    private var receiverRegistered: Boolean = false
    private var tickJob: Job? = null
    private var batteryJob: Job? = null
    private var statsJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> requestBatterySnapshot()
                Intent.ACTION_SCREEN_ON -> requestBatterySnapshot(screenStateOverride = true)
                Intent.ACTION_SCREEN_OFF -> requestBatterySnapshot(screenStateOverride = false)
            }
        }
    }

    init {
        restoreSession()
        refreshShizukuFlags()
        lastPermissionCheckMillis = System.currentTimeMillis()
        publish()
    }

    // =============================================================================================
    // Public contract used by PowerStatsViewModel
    // =============================================================================================

    /**
     * Starts (or resumes) monitoring: the 3 s heartbeat plus the battery and stats pipelines.
     *
     * The Shizuku permission dialog is shown immediately when the service is running but the app
     * has not been granted yet (once per process). Without the grant there is no data at all — the
     * UI shows its permission state and nothing is fabricated.
     *
     * A restored session keeps its original start time so "已使用" does not reset when the activity
     * is recreated; a cold start with no persisted session begins a new one at the current level.
     */
    fun startMonitoring() {
        if (monitoring) {
            requestBatterySnapshot()
            restartStatsPipeline(showLoading = false)
            return
        }
        monitoring = true
        registerBatteryReceiver()

        if (sessionStartMillis == null) {
            beginSession(lastSnapshot?.levelPercent)
        }

        lastPermissionCheckMillis = System.currentTimeMillis()
        refreshShizukuFlags()
        maybeRequestShizukuPermissionUpFront()

        lastTickAtMillis = System.currentTimeMillis()
        lastAccumulatorAtMillis = lastTickAtMillis
        requestBatterySnapshot(screenStateOverride = null, force = true)

        tickJob = scope.launch {
            while (isActive) {
                delay(PowerMath.TICK_INTERVAL_MILLIS)
                onTick()
            }
        }
        restartStatsPipeline(showLoading = true)
    }

    /**
     * Asks for shell access as soon as the app starts, exactly once per process.
     * If the service is not running, [PowerUiState.shizukuAvailable] stays `false` so the UI can
     * tell the user to start Shizuku/Sui.
     */
    private fun maybeRequestShizukuPermissionUpFront() {
        if (shizukuPermissionRequested || !ShizukuBridge.isServiceAvailable()) return
        if (ShizukuBridge.hasPermission()) return
        shizukuPermissionRequested = true
        ShizukuBridge.requestPermission { granted -> onShizukuPermissionResult(granted) }
    }

    /** Applies the result of the Shizuku permission dialog (may arrive on the binder thread). */
    private fun onShizukuPermissionResult(granted: Boolean) {
        scope.launch {
            shizukuGranted = granted
            shizukuAvailable = !granted && ShizukuBridge.isServiceAvailable()
            if (granted) {
                requestBatterySnapshot(force = true)
                restartStatsPipeline(showLoading = true)
            }
            publish()
        }
    }

    /** Stops all pipelines. The persisted session is kept so the chart survives a restart. */
    fun stopMonitoring() {
        monitoring = false
        tickJob?.cancel()
        tickJob = null
        batteryJob?.cancel()
        batteryJob = null
        statsJob?.cancel()
        statsJob = null
        unregisterBatteryReceiver()
        persistSession()
    }

    /**
     * Forces an immediate stats/scenario refresh. Cancels the in-flight fetch so the next result is
     * fresher instead of queued behind a slow `dumpsys` (the pipeline never overlaps anyway).
     */
    fun refreshScenarios() {
        if (monitoring) {
            restartStatsPipeline(showLoading = true)
        } else {
            // Not monitoring: still refresh the list once, without starting the loops.
            scope.launch {
                _state.update { it.copy(loadingScenarios = true) }
                runCatching { fetchStatsAndScenarios() }
                _state.update { it.copy(loadingScenarios = false) }
            }
        }
    }

    /**
     * Archives the running session into `sessionHistory` (newest first, max 20), then wipes the
     * samples / summary / session start / scenario list. Monitoring continues with a fresh session.
     */
    fun clearSessionData() {
        val now = System.currentTimeMillis()
        val start = sessionStartMillis
        val endLevel = lastSnapshot?.levelPercent ?: startLevelPercent

        if (start != null && now > start) {
            val duration = now - start
            val average = currentSessionAveragePower()
            val record = SessionRecord(
                startedAtMillis = start,
                durationMillis = duration,
                averagePowerWatts = average,
                energyWattHours = average * duration / MILLIS_PER_HOUR,
                startLevelPercent = startLevelPercent ?: endLevel ?: DEFAULT_LEVEL_PERCENT,
                endLevelPercent = endLevel ?: DEFAULT_LEVEL_PERCENT,
            )
            sessionHistory = (listOf(record) + sessionHistory).take(MAX_SESSION_HISTORY)
            store.saveHistory(sessionHistory)
        }

        windowSamples.clear()
        chartSamples = emptyList()
        historyChartSamples = emptyList()
        historySeedAllowed = false
        weightedPowerSum = 0.0
        weightSum = 0.0
        lastAccumulatorAtMillis = now
        lastTickAtMillis = now
        // Instantaneous power readings are *not* statistics: keep them so the first seconds after a
        // clear still show the real draw instead of falling back to the 0.05 W floor.
        energyAnchorWattHours = null
        energyAnchorMillis = 0L
        counterAnchorMicroAmpHours = null
        counterAnchorMillis = 0L
        visualCache = emptyMap()
        scenarios = emptyList()
        scenarioCandidates = emptyList()
        // The system dump feeds both the summary and the app list in Shizuku mode: drop it so the
        // freshly reset counters are picked up instead of the old cycle.
        lastPowerDump = null
        systemTimeOnBatteryMillis = 0L
        systemStatsFetchedAtMillis = 0L
        smoothedSystemAverageWatts = null

        store.clearSession()
        if (monitoring) {
            beginSession(lastSnapshot?.levelPercent)
        } else {
            sessionStartMillis = null
            startLevelPercent = lastSnapshot?.levelPercent ?: startLevelPercent
        }

        _state.update {
            it.copy(
                summary = PowerSummary(0f, 0L, 0L),
                chartSamples = emptyList(),
                scenarios = emptyList(),
                loadingScenarios = true,
                startLevelPercent = startLevelPercent ?: it.startLevelPercent,
                sessionHistory = sessionHistory,
            )
        }

        if (shizukuGranted) {
            // Best effort: wipe the system's own batterystats so the next query really starts from
            // zero (some ROMs refuse the command; that must not break the reset above).
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { ShizukuBridge.exec("dumpsys batterystats --reset") }
                }
                delay(SCENARIO_REFRESH_AFTER_RESET_MILLIS)
                if (isActive) restartStatsPipeline(showLoading = true)
            }
        } else {
            restartStatsPipeline(showLoading = true)
        }
    }

    /** Removes one finished session (identified by its start time) and persists the list. */
    fun deleteSessionRecord(startedAtMillis: Long) {
        val updated = sessionHistory.filterNot { it.startedAtMillis == startedAtMillis }
        if (updated.size == sessionHistory.size) return
        sessionHistory = updated
        store.saveHistory(updated)
        _state.update { it.copy(sessionHistory = updated) }
    }

    /** Wipes the whole finished-session history and persists the empty list. */
    fun clearSessionHistory() {
        if (sessionHistory.isEmpty()) return
        sessionHistory = emptyList()
        store.saveHistory(emptyList())
        _state.update { it.copy(sessionHistory = emptyList()) }
    }

    // =============================================================================================
    // Shizuku (optional shell access → system accounting)
    // =============================================================================================

    /** Re-reads the Shizuku state (service running? permission granted?) and publishes it. */
    fun refreshShizukuState() {
        val wasGranted = shizukuGranted
        refreshShizukuFlags()
        if (wasGranted != shizukuGranted) {
            if (!shizukuGranted) {
                shizukuPowerWatts = null
                smoothedSystemAverageWatts = null
            }
            requestBatterySnapshot(force = true)
            restartStatsPipeline(showLoading = false)
        }
        publish()
    }

    /** Shows Shizuku's permission dialog (explicit user action, e.g. the "去授权" button). */
    fun requestShizukuPermission() {
        if (!ShizukuBridge.isServiceAvailable()) {
            refreshShizukuState()
            return
        }
        shizukuPermissionRequested = true
        ShizukuBridge.requestPermission { granted -> onShizukuPermissionResult(granted) }
    }

    /** Reads the Shizuku flags without publishing. */
    private fun refreshShizukuFlags() {
        val available = runCatching { ShizukuBridge.isServiceAvailable() }.getOrDefault(false)
        val granted = available && runCatching { ShizukuBridge.hasPermission() }.getOrDefault(false)
        shizukuGranted = granted
        shizukuAvailable = available && !granted
    }

    // =============================================================================================
    // 1 s heartbeat
    // =============================================================================================

    /**
     * One heartbeat of the whole screen: fold the latest power into the session average, advance
     * the chart's right edge, refresh the battery snapshot (single-flight) and emit the state.
     */
    private fun onTick() {
        val now = System.currentTimeMillis()
        updateSessionAccumulator(now)
        advanceChartPoint(now)
        maybeRefreshShizukuState(now)
        requestBatterySnapshot()
        updateSystemAverageSmoothing(now)
        publish()
    }

    /** Time-weighted session mean, folded in on every tick so the average moves with the data. */
    private fun updateSessionAccumulator(nowMillis: Long) {
        val power = latestPowerWatts ?: lastValidPowerWatts
        val previous = lastAccumulatorAtMillis
        if (previous <= 0L) {
            lastAccumulatorAtMillis = nowMillis
            return
        }
        if (power == null) return
        val delta = (nowMillis - previous).coerceIn(0L, PowerMath.MAX_SAMPLE_WEIGHT_MILLIS)
        lastAccumulatorAtMillis = nowMillis
        if (delta <= 0L) return
        weightedPowerSum += power.toDouble() * delta
        weightSum += delta.toDouble()
    }

    /**
     * Advances the "使用过程" curve by one second.
     *
     * A point is appended only when the level or the segment style changes; otherwise the newest
     * point's timestamp is moved forward, so the right edge stays live without storing 2 700
     * identical points.
     */
    private fun advanceChartPoint(nowMillis: Long) {
        if (!monitoring || sessionStartMillis == null) return
        val snapshot = lastSnapshot ?: return

        val previousTick = lastTickAtMillis
        val missedRecord = previousTick > 0L && PowerMath.isMissedRecord(nowMillis - previousTick)
        lastTickAtMillis = nowMillis

        val style = PowerMath.segmentStyle(snapshot.screenOn, missedRecord)
        val level = snapshot.levelPercent
        val last = windowSamples.lastOrNull()

        if (last != null && last.levelPercent == level && last.style == style) {
            windowSamples[windowSamples.lastIndex] = last.copy(timestampMillis = nowMillis)
        } else {
            windowSamples.add(
                PowerSample(
                    timestampMillis = nowMillis,
                    levelPercent = level,
                    voltageVolts = snapshot.voltageVolts,
                    temperatureCelsius = snapshot.temperatureCelsius,
                    currentMilliAmps = snapshot.currentMilliAmps,
                    powerWatts = latestPowerWatts ?: lastValidPowerWatts ?: snapshot.powerWatts,
                    charging = snapshot.charging,
                    remainingEnergyWattHours = snapshot.remainingEnergyWattHours,
                    screenOn = snapshot.screenOn,
                    style = style,
                ),
            )
        }
        trimWindow(nowMillis)
        rebuildChartSamples()
        persistSamplesIfDue(nowMillis)
    }

    /** Rebuilds the lightweight chart list from the detailed window (≤ a few hundred points). */
    private fun rebuildChartSamples() {
        val samples = ArrayList<ChartSample>(windowSamples.size)
        for (sample in windowSamples) {
            samples += ChartSample(sample.timestampMillis, sample.levelPercent, sample.style)
        }
        chartSamples = samples
    }

    /** Drops everything older than the chart window, then enforces a hard point cap. */
    private fun trimWindow(nowMillis: Long) {
        val threshold = nowMillis - PowerMath.CHART_WINDOW_MILLIS
        while (windowSamples.isNotEmpty() && windowSamples.first().timestampMillis < threshold) {
            windowSamples.removeAt(0)
        }
        while (windowSamples.size > MAX_CHART_SAMPLES) {
            windowSamples.removeAt(0)
        }
    }

    /** `SharedPreferences` is only rewritten every [SAMPLE_PERSIST_INTERVAL_MILLIS], not per tick. */
    private fun persistSamplesIfDue(nowMillis: Long) {
        if (nowMillis - lastSamplesPersistedAtMillis < SAMPLE_PERSIST_INTERVAL_MILLIS) return
        lastSamplesPersistedAtMillis = nowMillis
        store.saveSamples(windowSamples)
        store.saveAccumulators(weightedPowerSum, weightSum)
    }

    /** Periodically notices Shizuku (or Sui) being started / stopped / revoked. */
    private fun maybeRefreshShizukuState(nowMillis: Long) {
        if (nowMillis - lastPermissionCheckMillis < PERMISSION_CHECK_INTERVAL_MILLIS) return
        lastPermissionCheckMillis = nowMillis

        val wasGranted = shizukuGranted
        refreshShizukuFlags()
        if (wasGranted == shizukuGranted) return

        if (shizukuGranted) {
            requestBatterySnapshot(force = true)
            restartStatsPipeline(showLoading = false)
        } else {
            // Permission revoked: stop showing numbers that can no longer be read.
            shizukuPowerWatts = null
            smoothedSystemAverageWatts = null
            lastPowerDump = null
            lastBatteryDump = null
            scenarios = emptyList()
            scenarioCandidates = emptyList()
            foregroundIntervals = emptyMap()
            _state.update {
                it.copy(
                    snapshot = null,
                    summary = PowerSummary(0f, 0L, 0L),
                    scenarios = emptyList(),
                    loadingScenarios = false,
                )
            }
        }
    }

    // =============================================================================================
    // Battery snapshot pipeline (single-flight, once per tick)
    // =============================================================================================

    /**
     * Refreshes the battery snapshot if none is in flight: one `dumpsys battery` per tick
     * (~100 ms) executed on `Dispatchers.IO`. A slow exec simply means the next tick re-emits the
     * previous numbers. Without the Shizuku permission there is nothing to read and no state is
     * fabricated.
     */
    private fun requestBatterySnapshot(screenStateOverride: Boolean? = null, force: Boolean = false) {
        if (!monitoring && !force) return
        if (!shizukuGranted) return
        if (batteryJob?.isActive == true) return

        batteryJob = scope.launch {
            val text = withContext(Dispatchers.IO) { ShizukuBridge.exec("dumpsys battery") }
            val dump = text?.let {
                runCatching { SystemBatteryStatsSource.parseBatteryDump(it) }.getOrNull()
            }
            if (dump != null) {
                val now = System.currentTimeMillis()
                updateChargeCounterPower(dump, now)
                lastBatteryDump = dump
                lastBatteryDumpMillis = now
            }
            applySnapshot(dump, screenStateOverride)
        }
    }

    /**
     * Turns the charge-counter change between two `dumpsys battery` reads into a wattage.
     * The counter moves in 1 mAh steps (~every 5 s at 0.6 A), so the anchor is kept until it moves
     * and the window is long enough for the step to mean something.
     */
    private fun updateChargeCounterPower(dump: SystemBatteryStatsSource.BatteryDump, nowMillis: Long) {
        val counter = dump.chargeCounterMicroAmpHours ?: return
        val anchor = counterAnchorMicroAmpHours
        val anchorAt = counterAnchorMillis
        if (anchor == null || anchorAt <= 0L) {
            counterAnchorMicroAmpHours = counter
            counterAnchorMillis = nowMillis
            return
        }

        val elapsedMillis = nowMillis - anchorAt
        if (elapsedMillis <= 0L) {
            counterAnchorMicroAmpHours = counter
            counterAnchorMillis = nowMillis
            return
        }
        val deltaMicroAmpHours = abs(counter - anchor)
        if (deltaMicroAmpHours <= 0L) return
        if (elapsedMillis < MIN_COUNTER_POWER_WINDOW_MILLIS) return

        val voltage = dump.voltageVolts ?: lastSnapshot?.voltageVolts ?: DEFAULT_VOLTAGE_VOLTS
        val hours = elapsedMillis / 3_600_000f
        if (hours <= 0f) return
        shizukuPowerWatts = PowerMath.clampPower(
            deltaMicroAmpHours / 1_000_000f * voltage / hours,
        )
        counterAnchorMicroAmpHours = counter
        counterAnchorMillis = nowMillis
    }

    /**
     * Folds a `dumpsys battery` result into [lastSnapshot].
     *
     * Every value comes from the dump (level / voltage / tenths of °C / charge counter / status);
     * the screen state comes from the broadcast override or `PowerManager`. Power is the
     * charge-counter delta when it moved, otherwise the fuel-gauge delta or the last good value.
     */
    private fun applySnapshot(
        incoming: SystemBatteryStatsSource.BatteryDump?,
        screenStateOverride: Boolean?,
    ) {
        if (!shizukuGranted) return
        val now = System.currentTimeMillis()
        val dump = incoming ?: lastBatteryDump
            ?.takeIf { now - lastBatteryDumpMillis <= SHIZUKU_DUMP_FRESH_MILLIS }
            ?: return

        val voltage = dump.voltageVolts ?: lastSnapshot?.voltageVolts ?: DEFAULT_VOLTAGE_VOLTS
        val levelPercent = dump.levelPercent ?: lastSnapshot?.levelPercent ?: 0
        val temperatureCelsius = dump.temperatureCelsius ?: lastSnapshot?.temperatureCelsius ?: 0f
        val charging = dump.charging
        val screenOn = screenStateOverride ?: lastSnapshot?.screenOn ?: sampler.isScreenOn()
        val currentMilliAmps = dump.currentMilliAmps
            ?.let { if (charging) it else -it }
            ?: lastSnapshot?.currentMilliAmps
            ?: 0f

        val energyFromCounter = PowerMath.energyFromChargeCounter(
            dump.chargeCounterMicroAmpHours,
            voltage,
        )
        val remainingEnergyWattHours = energyFromCounter
            ?: PowerMath.energyFromLevelPercent(
                levelPercent = levelPercent,
                voltageVolts = voltage,
                capacityMilliAmpHours = designCapacityMilliAmpHours?.toFloat()
                    ?: PowerMath.FALLBACK_CAPACITY_MILLIAMP_HOURS,
            )

        // NOTE: the OEM `Battery current : N` line of `dumpsys battery` is *not* the pack current on
        // this ROM (it reads ~146 mA while the fuel gauge shows ~620 mA), so it is used for display
        // only and never for power. The charge-counter delta is the authority.
        val energyEstimate = updateEnergyAnchor(remainingEnergyWattHours, energyFromCounter != null, now)
        val measuredPower = shizukuPowerWatts ?: energyEstimate
        val powerWatts = measuredPower
            ?: smoothedSystemAverageWatts
            ?: lastValidPowerWatts
            ?: currentSessionAveragePower().takeIf { it > 0f }
            ?: PowerMath.MIN_POWER_WATTS
        if (measuredPower != null) lastValidPowerWatts = powerWatts

        recentPowers.addLast(powerWatts)
        while (recentPowers.size > PowerMath.SMOOTHING_WINDOW) recentPowers.removeFirst()
        val smoothedPower = recentPowers.average().toFloat()

        val snapshot = BatterySnapshot(
            timestampMillis = now,
            levelPercent = levelPercent,
            voltageVolts = voltage,
            temperatureCelsius = temperatureCelsius,
            currentMilliAmps = currentMilliAmps,
            powerWatts = smoothedPower,
            charging = charging,
            remainingEnergyWattHours = remainingEnergyWattHours,
            screenOn = screenOn,
        )
        lastSnapshot = snapshot
        latestPowerWatts = smoothedPower

        if (monitoring && sessionStartMillis != null) {
            // Records a style change (screen on/off) immediately instead of waiting for the tick.
            advanceChartPoint(now)
        }
        publish()
    }

    /**
     * Keeps a stored-energy "anchor" and turns the change since it into an average power.
     * Shorter than [MIN_FUEL_GAUGE_WINDOW_MILLIS] a charge-counter step is just resolution noise.
     */
    private fun updateEnergyAnchor(currentWattHours: Float?, precise: Boolean, nowMillis: Long): Float? {
        if (currentWattHours == null) return null
        val anchor = energyAnchorWattHours
        val anchorMillis = energyAnchorMillis
        if (anchor == null || anchorMillis <= 0L) {
            energyAnchorWattHours = currentWattHours
            energyAnchorMillis = nowMillis
            return null
        }

        val elapsedMillis = nowMillis - anchorMillis
        if (elapsedMillis <= 0L) {
            energyAnchorWattHours = currentWattHours
            energyAnchorMillis = nowMillis
            return null
        }

        val minDelta = if (precise) MIN_FUEL_GAUGE_STEP_WATT_HOURS else MIN_LEVEL_STEP_WATT_HOURS
        val delta = abs(anchor - currentWattHours)
        if (delta < minDelta) return null
        if (elapsedMillis < (if (precise) MIN_FUEL_GAUGE_WINDOW_MILLIS else MIN_LEVEL_WINDOW_MILLIS)) {
            return null
        }

        energyAnchorWattHours = currentWattHours
        energyAnchorMillis = nowMillis
        return PowerMath.powerFromEnergyDelta(anchor, currentWattHours, elapsedMillis)
    }

    // =============================================================================================
    // Stats + scenario pipeline (serial, never overlapping)
    // =============================================================================================

    /**
     * (Re)starts the serial stats pipeline.
     *
     * The loop only starts the next `dumpsys` after the previous one finished, plus a ≥1 s floor,
     * so work can never pile up. [immediate] cancels an in-flight fetch, so a deliberate refresh is
     * not stuck behind a slow exec.
     */
    private fun restartStatsPipeline(showLoading: Boolean = false) {
        statsJob?.cancel()
        if (showLoading) _state.update { it.copy(loadingScenarios = true) }

        statsJob = scope.launch {
            while (isActive) {
                val startedAt = SystemClock.elapsedRealtime()
                runCatching { fetchStatsAndScenarios() }
                _state.update { state -> state.copy(loadingScenarios = false) }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val wait = (MIN_STATS_REFRESH_INTERVAL_MILLIS - elapsed).coerceAtLeast(0L)
                if (wait > 0L) delay(wait)
            }
        }
    }

    /** One iteration of the stats pipeline; suspends on IO, applies on main. */
    private suspend fun fetchStatsAndScenarios() {
        refreshShizukuFlags()
        if (!shizukuGranted) {
            // Nothing can be read without the shell permission: drop the list instead of guessing.
            scenarioCandidates = emptyList()
            scenarios = emptyList()
            visualCache = emptyMap()
            _state.update { it.copy(scenarios = emptyList(), loadingScenarios = false) }
            return
        }

        val fetchedAt = System.currentTimeMillis()
        // The full history dump is ~150 KiB and is only needed for the chart seed and the
        // foreground intervals: start-up + hourly, plus an early refresh when an app newly came to
        // the foreground (rate-limited) so its row gets the whole-device attribution.
        val needsIntervals = lastPowerDump?.let { dump ->
            withContext(Dispatchers.IO) { hasForegroundWithoutIntervals(dump) }
        } ?: false
        val timeSinceHistory = fetchedAt - lastHistoryFetchMillis
        val historyDue = historySeedAllowed && (
            lastHistoryFetchMillis == 0L ||
                timeSinceHistory >= HISTORY_REFRESH_INTERVAL_MILLIS ||
                (needsIntervals && timeSinceHistory >= MIN_HISTORY_RETRY_MILLIS)
            )
        val statsText = withContext(Dispatchers.IO) {
            if (historyDue) {
                ShizukuBridge.exec("dumpsys batterystats --charged --history")
                    ?: ShizukuBridge.exec("dumpsys batterystats --charged")
            } else {
                ShizukuBridge.exec("dumpsys batterystats --charged")
            }
        }
        if (statsText != null) {
            val powerDump = SystemBatteryStatsSource.parsePowerDump(statsText)
            val windowStart = fetchedAt - PowerMath.CHART_WINDOW_MILLIS
            val windowEnd = fetchedAt + HISTORY_CLOCK_SKEW_MILLIS
            val history = if (historyDue) {
                SystemBatteryStatsSource.parseBatteryHistory(statsText, windowStart, windowEnd, fetchedAt)
            } else {
                emptyList()
            }
            val intervals = if (historyDue) {
                SystemBatteryStatsSource.parseForegroundIntervals(statsText, windowStart, windowEnd, fetchedAt)
            } else {
                null
            }
            applyStats(powerDump, history, intervals, fetchedAt)
            if (historyDue) lastHistoryFetchMillis = fetchedAt
        }

        val candidates = withContext(Dispatchers.IO) {
            runCatching { resolveShizukuCandidates(lastPowerDump?.uidPower.orEmpty()) }
                .getOrElse { emptyList() }
        }
        applyScenarioCandidates(candidates)
    }

    /**
     * Converts real history points into chart segments. A gap larger than the missed-record
     * threshold (the system logged nothing) becomes the dotted `MissedRecord*` variant; levels are
     * never interpolated.
     */
    private fun toChartSamples(
        points: List<SystemBatteryStatsSource.HistoryPoint>,
    ): List<ChartSample> {
        if (points.isEmpty()) return emptyList()
        val samples = ArrayList<ChartSample>(points.size)
        var previousTimestamp = 0L
        for (point in points) {
            val missed = previousTimestamp > 0L &&
                PowerMath.isMissedRecord(point.timestampMillis - previousTimestamp)
            samples += ChartSample(
                timestampMillis = point.timestampMillis,
                levelPercent = point.levelPercent,
                style = PowerMath.segmentStyle(point.screenOn, missed),
            )
            previousTimestamp = point.timestampMillis
        }
        return samples
    }

    /** Applies a fresh `dumpsys batterystats` result plus optional chart history. */
    private fun applyStats(
        powerDump: SystemBatteryStatsSource.PowerDump?,
        history: List<SystemBatteryStatsSource.HistoryPoint>,
        intervals: Map<String, List<TimeInterval>>?,
        fetchedAtMillis: Long,
    ) {
        if (intervals != null) {
            foregroundIntervals = intervals
            foregroundIntervalsFetchedAtMillis = fetchedAtMillis
        }
        if (powerDump != null) {
            powerDump.capacityMilliAmpHours?.let { designCapacityMilliAmpHours = it }
            powerDump.learnedCapacityMilliAmpHours?.let { learnedCapacityMilliAmpHours = it }
            lastPowerDump = powerDump
            powerDump.timeOnBatteryMillis?.let {
                systemTimeOnBatteryMillis = it
                systemStatsFetchedAtMillis = fetchedAtMillis
            }
        }

        if (historySeedAllowed && history.size >= MIN_HISTORY_POINTS) {
            historyChartSamples = toChartSamples(history)
        }
    }

    /** Builds the per-app list (labels/icons via `PackageManager`) and replaces it if it changed. */
    private fun applyScenarioCandidates(candidates: List<ScenarioCandidate>) {
        val visuals = runCatching {
            packageVisuals.visualsFor(candidates.map { it.packageName })
        }.getOrElse { emptyMap() }

        scenarioCandidates = candidates
        visualCache = visuals
        val built = buildScenarios()
        if (built != scenarios) scenarios = built
        _state.update {
            it.copy(scenarios = scenarios, loadingScenarios = false)
        }
    }

    // =============================================================================================
    // Usage scenarios
    // =============================================================================================

    /**
     * `true` when a UID logged screen-foreground time but we have no interval for its package yet
     * (the app was opened after the last history dump). Runs on `Dispatchers.IO`.
     */
    private fun hasForegroundWithoutIntervals(dump: SystemBatteryStatsSource.PowerDump): Boolean {
        for (entry in dump.uidPower) {
            if ((entry.foregroundMillis ?: 0L) <= 0L) continue
            val packages = packageVisuals.packagesForUid(entry.uid)
                .filter { packageVisuals.isReportablePackage(it) }
            if (packages.isEmpty()) continue
            if (packages.none { foregroundIntervals.containsKey(it) }) return true
        }
        return false
    }

    /**
     * Foreground intervals for [packageName], with the still-open interval extended to "now".
     *
     * The history is only re-read every [HISTORY_REFRESH_INTERVAL_MILLIS], so the app that is
     * currently on screen would otherwise stop collecting samples between two history fetches.
     */
    private fun foregroundIntervalsFor(packageName: String, nowMillis: Long): List<TimeInterval> {
        val intervals = foregroundIntervals[packageName].orEmpty()
        if (intervals.isEmpty()) return emptyList()
        val fetchedAt = foregroundIntervalsFetchedAtMillis
        val result = ArrayList<TimeInterval>(intervals.size)
        for ((index, interval) in intervals.withIndex()) {
            val isLast = index == intervals.lastIndex
            val stillOpen = fetchedAt > 0L && interval.endMillis >= fetchedAt - OPEN_INTERVAL_SLACK_MILLIS
            result += if (isLast && stillOpen) {
                TimeInterval(interval.startMillis, maxOf(interval.endMillis, nowMillis))
            } else {
                interval
            }
        }
        return result
    }

    /**
     * Shizuku path: one candidate per UID from the batterystats dump.
     *
     * Energy comes from the UID's total mAh, duration from its foreground slices (`fg` + `fgs`,
     * falling back to any recorded time), and the package name for the label/icon from
     * `PackageManager`. Runs on `Dispatchers.IO`.
     */
    private fun resolveShizukuCandidates(
        uidPower: List<SystemBatteryStatsSource.UidPower>,
    ): List<ScenarioCandidate> {
        val fallbackDuration = lastPowerDump?.timeOnBatteryMillis?.takeIf { it > 0L }
            ?: sessionElapsedMillis()
        // A UID without any recorded state time has no usable usage duration. Keep it only when it
        // is a significant energy consumer; otherwise it would show up with a fabricated duration.
        val significantMilliAmpHours = (lastPowerDump?.dischargeMilliAmpHours ?: 0f) *
            MIN_UID_ENERGY_SHARE_OF_DISCHARGE
        val byPackage = LinkedHashMap<String, ScenarioCandidate>()
        val now = System.currentTimeMillis()

        for (entry in uidPower) {
            // System UIDs are shared between many packages: prefer a launchable one, but keep
            // non-launchable system apps too (the reference screen lists 系统界面/系统桌面 etc.).
            val packages = packageVisuals.packagesForUid(entry.uid)
                .filter { packageVisuals.isReportablePackage(it) }
            val packageName = packages.firstOrNull { packageVisuals.isLaunchableOrLauncher(it) }
                ?: packages.firstOrNull()
                ?: continue

            val intervals = foregroundIntervalsFor(packageName, now)
            val hadScreenForeground = (entry.foregroundMillis ?: 0L) > 0L || intervals.isNotEmpty()
            // Background-service only rows (GMS, location/decision services…) carry a few µAh and no
            // screen time; the reference never lists them and they would only add 0.01 W noise.
            if (!hadScreenForeground) continue

            val measuredDuration = entry.foregroundDurationMillis
                .takeIf { it > 0L }
                ?: entry.totalDurationMillis.takeIf { it > 0L }
            if (measuredDuration == null && entry.milliAmpHours < significantMilliAmpHours) continue
            val duration = measuredDuration ?: fallbackDuration
            if (duration <= 0L) continue

            val existing = byPackage[packageName]
            if (existing == null || entry.milliAmpHours > (existing.uidMilliAmpHours ?: 0f)) {
                byPackage[packageName] = ScenarioCandidate(
                    packageName = packageName,
                    foregroundMillis = duration,
                    intervals = intervals,
                    uidMilliAmpHours = entry.milliAmpHours,
                    uidActiveMillis = measuredDuration,
                    hadScreenForeground = true,
                )
            }
        }

        // Rank by watts; every app is kept (the card scrolls), nothing is truncated.
        return byPackage.values.sortedByDescending { it.uidMilliAmpHours ?: 0f }
    }

    /**
     * Per-app numbers for the "使用场景" card.
     *
     * Power: the system's per-UID mAh over the app's foreground time when available
     * (`mAh / 1000 × V / h`), otherwise the samples that fall inside the app's foreground intervals
     * (falling back to the session average). Temperature comes from the samples.
     */
    private fun buildScenarios(): List<UsageScenario> {
        val candidates = scenarioCandidates
        if (candidates.isEmpty()) return emptyList()

        val samples = windowSamples.toList()
        val sessionAveragePower = currentSessionAveragePower()
        // Scene's definition for a row is "the device's average draw while that app was on screen".
        // When our own samples do not cover the interval (it happened before this process started),
        // the chart-window average is the closest whole-device number.
        val windowAveragePower = PowerMath.timeWeightedMeanPower(samples)
        val windowSpanMillis = if (samples.size >= 2) {
            samples.last().timestampMillis - samples.first().timestampMillis
        } else {
            0L
        }
        val windowAverageReliable = samples.size >= MIN_SAMPLES_FOR_WINDOW_AVERAGE &&
            windowSpanMillis >= MIN_SPAN_FOR_WINDOW_AVERAGE
        val voltage = lastSnapshot?.voltageVolts
            ?: lastBatteryDump?.voltageVolts
            ?: DEFAULT_VOLTAGE_VOLTS
        val (fallbackTemperature, fallbackMaxTemperature) = windowTemperatureStats(samples)
        val summary = currentSummary()
        val sessionDurationMillis = summary.usedMillis.takeIf { it > 0L }
            ?: sessionElapsedMillis()
        // Device draw when we have no sample inside the app's foreground interval (e.g. the app ran
        // before this process started). The charge-cycle average is the best available proxy and
        // keeps the list on the same scale as the summary.
        val cycleAveragePower = summary.averagePowerWatts.takeIf { it > 0f }

        val result = ArrayList<UsageScenario>(candidates.size)
        for (candidate in candidates) {
            val visual = visualCache[candidate.packageName] ?: continue
            val metrics = PowerMath.metricsForIntervals(samples, candidate.intervals, sessionAveragePower)
            val durationMillis = candidate.foregroundMillis.takeIf { it > 0L }
                ?: candidate.uidActiveMillis?.takeIf { it > 0L }
                ?: sessionDurationMillis
            val uidPowerWatts = powerFromUidMilliAmpHours(
                milliAmpHours = candidate.uidMilliAmpHours,
                activeMillis = candidate.uidActiveMillis,
                sessionMillis = sessionDurationMillis,
                voltageVolts = voltage,
            )
            // Precedence: our own samples inside the foreground interval (exactly what Scene
            // shows) → whole-device chart-window average when the interval predates our samples →
            // the cycle average → UID mAh share (only when there is no foreground interval at all).
            val perAppPowerWatts = metrics?.weightedPowerWatts
                ?: if (candidate.intervals.isNotEmpty()) {
                    val deviceAverage = if (windowAverageReliable) {
                        windowAveragePower.takeIf { it > 0f } ?: cycleAveragePower
                    } else {
                        cycleAveragePower
                    }
                    deviceAverage ?: uidPowerWatts ?: sessionAveragePower
                } else {
                    uidPowerWatts ?: sessionAveragePower
                }
            result += UsageScenario(
                packageName = candidate.packageName,
                label = visual.label,
                icon = visual.icon,
                durationMillis = durationMillis,
                averagePowerWatts = perAppPowerWatts,
                averageTemperatureCelsius = metrics?.averageTemperatureCelsius ?: fallbackTemperature,
                maxTemperatureCelsius = metrics?.maxTemperatureCelsius ?: fallbackMaxTemperature,
            )
        }
        // The card always sorts by watts; doing it here keeps the state itself consistent.
        return result.sortedByDescending { it.averagePowerWatts }
    }

    /**
     * `mAh / 1000 × V / hours` — the system's own energy accounting turned into an average draw.
     *
     * The UID figure is app-attributed energy only (batterystats accounts screen/radio separately),
     * so the floor is [PowerMath.MIN_APP_POWER_WATTS] rather than the device-wide 0.05 W.
     */
    private fun powerFromUidMilliAmpHours(
        milliAmpHours: Float?,
        activeMillis: Long?,
        sessionMillis: Long,
        voltageVolts: Float,
    ): Float? {
        if (milliAmpHours == null || milliAmpHours <= 0f) return null
        val active = activeMillis?.takeIf { it > 0L }
            ?: sessionMillis.takeIf { it > 0L }
            ?: return null
        val hours = active / 3_600_000f
        if (hours <= 0f) return null
        return PowerMath.clampAppPower(milliAmpHours / 1000f * voltageVolts / hours)
    }

    /** Mean / max battery temperature over the samples currently in the chart window. */
    private fun windowTemperatureStats(samples: List<PowerSample>): Pair<Float, Float> {
        if (samples.isEmpty()) {
            val fallback = lastSnapshot?.temperatureCelsius ?: 0f
            return fallback to fallback
        }
        var sum = 0f
        var max = Float.NEGATIVE_INFINITY
        for (sample in samples) {
            sum += sample.temperatureCelsius
            if (sample.temperatureCelsius > max) max = sample.temperatureCelsius
        }
        return (sum / samples.size) to max
    }

    /** Wall-clock time since monitoring started, independent of the summary's data source. */
    private fun sessionElapsedMillis(): Long {
        val start = sessionStartMillis ?: return 0L
        return (System.currentTimeMillis() - start).coerceAtLeast(0L)
    }

    // =============================================================================================
    // Session lifecycle / persistence
    // =============================================================================================

    private fun beginSession(levelPercent: Int?) {
        val now = System.currentTimeMillis()
        sessionStartMillis = now
        startLevelPercent = levelPercent
        windowSamples.clear()
        chartSamples = emptyList()
        weightedPowerSum = 0.0
        weightSum = 0.0
        lastAccumulatorAtMillis = now
        lastTickAtMillis = now
        energyAnchorWattHours = null
        energyAnchorMillis = 0L
        counterAnchorMicroAmpHours = null
        counterAnchorMillis = 0L

        store.clearSession()
        store.saveSessionStartMillis(now)
        store.saveStartLevelPercent(levelPercent)
    }

    private fun restoreSession() {
        val storedStart = store.loadSessionStartMillis() ?: return
        sessionStartMillis = storedStart
        startLevelPercent = store.loadStartLevelPercent()

        val (storedWeightedPower, storedWeight) = store.loadAccumulators()
        weightedPowerSum = storedWeightedPower
        weightSum = storedWeight

        val threshold = System.currentTimeMillis() - PowerMath.CHART_WINDOW_MILLIS
        for (sample in store.loadSamples()) {
            if (sample.timestampMillis >= threshold) windowSamples.add(sample)
        }

        rebuildChartSamples()
        lastAccumulatorAtMillis = System.currentTimeMillis()
    }

    private fun persistSession() {
        store.saveSessionStartMillis(sessionStartMillis)
        store.saveStartLevelPercent(startLevelPercent)
        store.saveAccumulators(weightedPowerSum, weightSum)
        store.saveSamples(windowSamples)
        store.saveHistory(sessionHistory)
    }

    // =============================================================================================
    // State assembly
    // =============================================================================================

    private fun publish() {
        val summary = currentSummary()
        val mergedChartSamples = mergedChartSamples()
        _state.update {
            it.copy(
                snapshot = lastSnapshot,
                summary = summary,
                chartSamples = mergedChartSamples,
                chartWindowMillis = PowerMath.CHART_WINDOW_MILLIS,
                startLevelPercent = startLevelPercent ?: lastSnapshot?.levelPercent ?: it.startLevelPercent,
                sessionHistory = sessionHistory,
                scenarios = scenarios,
                fullCapacityWattHours = fullCapacityWattHours(),
                shizukuGranted = shizukuGranted,
                shizukuAvailable = shizukuAvailable,
            )
        }
    }

    /**
     * History first, then the live samples for the most recent minutes. Deduplicated because the
     * history keeps covering the same wall-clock minutes while the app is running.
     */
    private fun mergedChartSamples(): List<ChartSample> {
        val live = chartSamples
        val history = historyChartSamples
        if (history.isEmpty()) return live
        if (live.isEmpty()) return history
        val firstLiveTimestamp = live.first().timestampMillis
        val merged = ArrayList<ChartSample>(history.size + live.size)
        for (sample in history) {
            if (sample.timestampMillis < firstLiveTimestamp) merged += sample
        }
        merged += live
        return merged
    }

    /**
     * Summary for the three columns, recomputed on every tick.
     *
     * The numbers are the system's own charge-cycle accounting: `Discharge mAh → W` over
     * `Time on battery`, with the used time extrapolated between stats fetches so it keeps moving.
     * Without the Shizuku permission (or before the first dump) there is nothing honest to show,
     * so the summary stays zeroed instead of being estimated.
     */
    private fun currentSummary(): PowerSummary {
        if (!shizukuGranted) return PowerSummary(0f, 0L, 0L)
        val now = System.currentTimeMillis()
        val usedMillis = systemUsedMillis(now) ?: return PowerSummary(0f, 0L, 0L)
        val averagePowerWatts = smoothedSystemAverageWatts ?: return PowerSummary(0f, usedMillis, 0L)

        val remainingWattHours = lastSnapshot?.remainingEnergyWattHours
        // Deliberately *not* the 72 h-clamped estimate: with a correct average this lands around a
        // day, and a broken average must be visible rather than masked.
        val runtimeMillis = if (remainingWattHours != null && remainingWattHours > 0f) {
            (remainingWattHours / averagePowerWatts * 3_600_000f).toLong().coerceAtLeast(0L)
        } else {
            0L
        }
        return PowerSummary(
            averagePowerWatts = averagePowerWatts,
            usedMillis = usedMillis,
            estimatedRuntimeMillis = runtimeMillis,
        )
    }

    /**
     * Full-pack capacity in Wh, as shown by the "使用过程" card (`29.0Wh` on this phone).
     *
     * `7500 mAh (learned, health-adjusted) × 3.87 V ≈ 29.0 Wh`, computed **once** from the first
     * successful dump: it is a hardware property, not a live value. Without the Shizuku dump the
     * value is unknown (`null` → the card shows `--`).
     */
    private fun fullCapacityWattHours(): Float? {
        fullCapacityWattHoursCache?.let { return it }
        val capacityMilliAmpHours = learnedCapacityMilliAmpHours
            ?: designCapacityMilliAmpHours
            ?: return null
        if (capacityMilliAmpHours <= 0) return null
        val wattHours = capacityMilliAmpHours / 1000f * NOMINAL_VOLTAGE_VOLTS
        fullCapacityWattHoursCache = wattHours
        return wattHours
    }

    /** `Time on battery` from the last dump plus the wall-clock drift since that dump. */
    private fun systemUsedMillis(nowMillis: Long): Long? {
        val base = systemTimeOnBatteryMillis.takeIf { it > 0L } ?: return null
        val drift = (nowMillis - systemStatsFetchedAtMillis).coerceIn(0L, SYSTEM_TIME_DRIFT_CAP_MILLIS)
        return base + drift
    }

    /** Smooths the charge-cycle average so counter steps do not make the numbers flicker. */
    private fun updateSystemAverageSmoothing(nowMillis: Long) {
        if (!shizukuGranted) {
            smoothedSystemAverageWatts = null
            return
        }
        val dump = lastPowerDump
        val discharge = dump?.dischargeMilliAmpHours?.takeIf { it >= MIN_DISCHARGE_FOR_AVERAGE }
        val usedMillis = systemUsedMillis(nowMillis)
        if (dump == null || discharge == null || usedMillis == null) return
        if (usedMillis < MIN_TIME_ON_BATTERY_FOR_AVERAGE) return

        val voltage = lastSnapshot?.voltageVolts
            ?: lastBatteryDump?.voltageVolts
            ?: DEFAULT_VOLTAGE_VOLTS
        val hours = usedMillis / 3_600_000f
        if (hours <= 0f) return
        val raw = PowerMath.clampPower(discharge / 1000f * voltage / hours)
        smoothedSystemAverageWatts = smoothedSystemAverageWatts
            ?.let { it + (raw - it) * SYSTEM_AVERAGE_SMOOTHING }
            ?: raw
    }

    /** Time-weighted mean draw over the whole session; 0 until at least one tick was accumulated. */
    private fun currentSessionAveragePower(): Float {
        if (weightSum <= 0.0) return 0f
        return (weightedPowerSum / weightSum).toFloat()
            .coerceIn(0f, PowerMath.MAX_POWER_WATTS)
            .takeIf { it.isFinite() } ?: 0f
    }

    // =============================================================================================
    // Broadcast receiver
    // =============================================================================================

    private fun registerBatteryReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // All three are protected system broadcasts; NOT_EXPORTED is the correct (and required on
        // API 34+) registration flag. Fall back to the un-flagged overload if a ROM rejects it.
        val registered = runCatching {
            ContextCompat.registerReceiver(
                appContext,
                batteryReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
        receiverRegistered = registered || runCatching {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(batteryReceiver, filter)
        }.isSuccess
    }

    private fun unregisterBatteryReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
        receiverRegistered = false
    }

    private companion object {
        /**
         * `dumpsys batterystats --charged` cadence. The state tick is 3 s; the aggregate dump takes
         * 1–2 s, so it runs every second tick (6 s) to keep the shell load in check. The UI still
         * updates every 3 s from the cached aggregate.
         */
        const val MIN_STATS_REFRESH_INTERVAL_MILLIS = 6_000L

        /** `--history` (chart seed + foreground intervals) cadence: start-up, then hourly. */
        const val HISTORY_REFRESH_INTERVAL_MILLIS = 60L * 60_000L

        /** Extra history fetch when a new app came to the foreground (rate limit). */
        const val MIN_HISTORY_RETRY_MILLIS = 60_000L

        /** An interval that ends this close to the history fetch was still open. */
        const val OPEN_INTERVAL_SLACK_MILLIS = 15_000L

        /** The chart-window average is only trusted for the per-app fallback past these bounds. */
        const val MIN_SAMPLES_FOR_WINDOW_AVERAGE = 3
        const val MIN_SPAN_FOR_WINDOW_AVERAGE = 60_000L

        /** Li-ion nominal voltage used for the pack capacity (`7500 mAh × 3.87 V ≈ 29.0 Wh`). */
        const val NOMINAL_VOLTAGE_VOLTS = 3.87f

        /** SharedPreferences is written at this rate, not on every tick. */
        const val SAMPLE_PERSIST_INTERVAL_MILLIS = 60_000L

        /** Values from a `dumpsys battery` older than this are replaced by the SDK reading. */
        const val SHIZUKU_DUMP_FRESH_MILLIS = 8_000L

        /** Minimum window between two battery dumps before the counter delta is trusted. */
        const val MIN_COUNTER_POWER_WINDOW_MILLIS = 5_000L

        /** Periodic usage-access / Shizuku re-check (once per 3 s tick). */
        const val PERMISSION_CHECK_INTERVAL_MILLIS = 3_000L

        /** How far `Time on battery` may be extrapolated past the last stats fetch. */
        const val SYSTEM_TIME_DRIFT_CAP_MILLIS = 10_000L

        /** Low-pass factor for the charge-cycle average (steps in the mAh counter). */
        const val SYSTEM_AVERAGE_SMOOTHING = 0.25f

        /** Below these, the charge-cycle averages are reset/noise and must not be shown. */
        const val MIN_DISCHARGE_FOR_AVERAGE = 0.2f
        const val MIN_TIME_ON_BATTERY_FOR_AVERAGE = 20_000L

        /** A `--history` stamp a few seconds ahead of `now` is normal (clock skew). */
        const val HISTORY_CLOCK_SKEW_MILLIS = 5_000L

        /** Fewer reconstructed points than this means the history was missing/rotated away. */
        const val MIN_HISTORY_POINTS = 2

        /** Give `dumpsys batterystats --reset` a moment before reading the counters back. */
        const val SCENARIO_REFRESH_AFTER_RESET_MILLIS = 800L

        /** A UID without recorded state time must at least carry this share of the cycle drain. */
        const val MIN_UID_ENERGY_SHARE_OF_DISCHARGE = 0.01f

        /** Fuel-gauge anchor: window and resolution needed for a meaningful ΔWh (1 mAh steps). */
        const val MIN_FUEL_GAUGE_WINDOW_MILLIS = 20_000L
        const val MIN_FUEL_GAUGE_STEP_WATT_HOURS = 0.002f

        /** Level-only fallback: 1 % of a 7.5 Ah pack is too coarse for a short window. */
        const val MIN_LEVEL_WINDOW_MILLIS = 10L * 60_000L
        const val MIN_LEVEL_STEP_WATT_HOURS = 0.2f

        /** Finished sessions kept by "清理数据". */
        const val MAX_SESSION_HISTORY = 20

        /** Hard cap on chart points (the 45 min window normally holds a few hundred). */
        const val MAX_CHART_SAMPLES = 600

        const val DEFAULT_VOLTAGE_VOLTS = 4.2f
        const val DEFAULT_LEVEL_PERCENT = 100
        const val MILLIS_PER_HOUR = 3_600_000f
    }
}

/**
 * One row of the "使用场景" list before it becomes a [UsageScenario]: the system's per-UID energy
 * accounting (`uidMilliAmpHours`) plus the foreground intervals reconstructed from the batterystats
 * history, which drive the whole-device power attribution and the temperatures.
 */
private data class ScenarioCandidate(
    val packageName: String,
    val foregroundMillis: Long,
    val intervals: List<TimeInterval>,
    val uidMilliAmpHours: Float?,
    val uidActiveMillis: Long?,
    /** `true` when the app actually had screen time; background-service-only rows are noise. */
    val hadScreenForeground: Boolean,
)
