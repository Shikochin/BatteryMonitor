package dev.shiko.batterymonitor.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
 * The maths are aligned with Scene 9.4.18 (`Analyser-Power` + its SQL queries):
 *
 *  - a sample is recorded **only while discharging and the screen is on** (plus one final
 *    `screenOn = false` record when the screen turns off, which is what draws the grey chart
 *    segment); charging periods record nothing,
 *  - `平均功耗` = mean of `current(mA) × voltage(V)` over the session's screen-on samples,
 *  - `已使用` = `sampleCount × 3 s`,
 *  - `理论续航` = `remainingWh / avgPower × 0.9`,
 *  - `使用场景` = the same samples grouped per foreground package (no UID/whole-device fallback),
 *  - the chart breaks into dotted segments when the gap between samples exceeds 10 s.
 *
 * Data comes from Shizuku: sysfs when the service runs as root (Sui/KernelSU), `dumpsys battery`
 * otherwise, and `dumpsys batterystats` for the foreground intervals, the learned capacity and the
 * chart history seed. Without the permission there is no data at all (snapshot `null`, summary 0).
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

    // --- running session (Scene's records table) ---------------------------------------------------
    private var sessionStartMillis: Long? = null
    private var startLevelPercent: Int? = null

    /** Number of screen-on discharging samples in the session. */
    private var sessionSampleCount: Int = 0

    /** Σ(current_mA × voltage_V) in mW over those samples (negative while discharging). */
    private var sessionPowerMilliWattSum: Double = 0.0
    private val packageAggregates = LinkedHashMap<String, PackageAggregate>()

    private var lastSampleAtMillis: Long = 0L
    private var lastSampleLevel: Int = -1
    private var lastSampleWasScreenOn: Boolean = false

    // --- live reading ------------------------------------------------------------------------------
    private var lastReading: BatteryReading? = null
    private var lastRawReading: RawBatteryReading? = null
    private var lastReadingAtMillis: Long = 0L
    private var lastScreenOn: Boolean? = null
    private var lastSnapshot: BatterySnapshot? = null
    private var latestPowerWatts: Float? = null

    // --- charge counter → reference power for the current calibration -------------------------------
    private var counterAnchorMicroAmpHours: Long? = null
    private var counterAnchorMillis: Long = 0L
    private var counterReferenceWatts: Float? = null
    private var counterReferenceAtMillis: Long = 0L
    private var lastValidPowerWatts: Float? = null

    /** Raw-to-mA divisor locked by the calibrator, reused until the counter corrects it. */
    private var lockedCurrentDivisor: Float? = null
    private var lastCurrentSource: CurrentSource = CurrentSource.COUNTER

    // --- chart ------------------------------------------------------------------------------------
    private val windowSamples = mutableListOf<PowerSample>()
    private var chartSamples: List<ChartSample> = emptyList()
    private var historyChartSamples: List<ChartSample> = emptyList()
    private var historySeedAllowed: Boolean = true
    private var lastChartPointAtMillis: Long = 0L
    private var lastSamplesPersistedAtMillis: Long = 0L

    // --- capacity ---------------------------------------------------------------------------------
    private var learnedCapacityMilliAmpHours: Int? = null
    private var designCapacityMilliAmpHours: Int? = null
    private var chargeFullMilliAmpHours: Float? = null
    private var fullCapacityWattHoursCache: Float? = null

    // --- Shizuku / root ---------------------------------------------------------------------------
    private var shizukuAvailable: Boolean = false
    private var shizukuGranted: Boolean = false
    private var rootAvailable: Boolean = false
    /** Consecutive ticks where the sysfs read failed while root was assumed. */
    private var sysfsFailureStreak: Int = 0
    private var shizukuPermissionRequested: Boolean = false
    private var lastPermissionCheckMillis: Long = 0L

    // --- foreground intervals (from the batterystats history) -------------------------------------
    private var foregroundIntervals: Map<String, List<TimeInterval>> = emptyMap()
    private var intervalIndex: List<IntervalEntry> = emptyList()
    private var intervalsFetchedAtMillis: Long = 0L
    private var lastHistoryFetchMillis: Long = 0L
    private var lastPowerDump: SystemBatteryStatsSource.PowerDump? = null

    /** Set when a sample could not be attributed to a package; triggers an early history refresh. */
    private var unattributedSampleSeen: Boolean = false

    // --- per-app presentation ---------------------------------------------------------------------
    private var scenarios: List<UsageScenario> = emptyList()
    private var visualCache: Map<String, PackageVisuals.AppVisual> = emptyMap()

    // --- lifecycle --------------------------------------------------------------------------------
    private var monitoring: Boolean = false
    private var receiverRegistered: Boolean = false
    private var tickJob: Job? = null
    private var batteryJob: Job? = null
    private var statsJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> requestBatteryReading()
                Intent.ACTION_SCREEN_ON -> requestBatteryReading(screenStateOverride = true)
                Intent.ACTION_SCREEN_OFF -> requestBatteryReading(screenStateOverride = false)
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
     * Starts (or resumes) monitoring: the 3 s heartbeat, the battery reader and the stats pipeline.
     * The Shizuku permission dialog is requested once per process when the service runs but the app
     * has not been granted access yet.
     */
    fun startMonitoring() {
        if (monitoring) {
            requestBatteryReading(force = true)
            restartStatsPipeline(showLoading = false)
            return
        }
        monitoring = true
        registerBatteryReceiver()

        if (sessionStartMillis == null) beginSession(lastReading?.levelPercent ?: lastSnapshot?.levelPercent)

        lastPermissionCheckMillis = System.currentTimeMillis()
        refreshShizukuFlags()
        maybeRequestShizukuPermissionUpFront()

        requestBatteryReading(screenStateOverride = null, force = true)
        tickJob = scope.launch {
            while (isActive) {
                delay(PowerMath.TICK_INTERVAL_MILLIS)
                onTick()
            }
        }
        restartStatsPipeline(showLoading = true)
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

    /** Re-reads the app list (labels/icons) and the system numbers. */
    fun refreshScenarios() {
        if (monitoring) {
            restartStatsPipeline(showLoading = true)
        } else {
            scope.launch {
                _state.update { it.copy(loadingScenarios = true) }
                runCatching { refreshStats() }
                _state.update { it.copy(loadingScenarios = false) }
            }
        }
    }

    /**
     * Archives the running session into `sessionHistory` (newest first, max 20), wipes the samples /
     * summary / session start / scenario list and resets the system counters through Shizuku.
     */
    fun clearSessionData() {
        val now = System.currentTimeMillis()
        archiveCurrentSession(now, lastReading?.levelPercent ?: startLevelPercent)
        resetSessionState(now, lastReading?.levelPercent ?: lastSnapshot?.levelPercent, allowHistorySeed = false)

        store.clearSession()
        lastReading = null
        lastSnapshot = null
        latestPowerWatts = null
        lastPowerDump = null
        visualCache = emptyMap()
        scenarios = emptyList()

        _state.update {
            it.copy(
                snapshot = null,
                summary = PowerSummary(0f, 0L, 0L),
                chartSamples = emptyList(),
                scenarios = emptyList(),
                loadingScenarios = true,
                startLevelPercent = startLevelPercent ?: it.startLevelPercent,
                sessionHistory = sessionHistory,
            )
        }

        scope.launch {
            if (shizukuGranted) {
                // Best effort: wipe the system's own batterystats so the next query starts from zero.
                withContext(Dispatchers.IO) {
                    runCatching { ShizukuBridge.exec("dumpsys batterystats --reset") }
                }
            }
            delay(SCENARIO_REFRESH_AFTER_RESET_MILLIS)
            if (isActive) {
                requestBatteryReading(force = true)
                restartStatsPipeline(showLoading = true)
            }
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

    /** Re-reads the Shizuku state (service running? permission granted? root or shell?). */
    fun refreshShizukuState() {
        val wasGranted = shizukuGranted
        refreshShizukuFlags()
        if (wasGranted != shizukuGranted) {
            if (shizukuGranted) {
                requestBatteryReading(force = true)
                restartStatsPipeline(showLoading = false)
            } else {
                clearUnavailableData()
            }
        }
        publish()
    }

    /** Shows Shizuku's permission dialog (explicit user action). */
    fun requestShizukuPermission() {
        if (!ShizukuBridge.isServiceAvailable()) {
            refreshShizukuState()
            return
        }
        shizukuPermissionRequested = true
        ShizukuBridge.requestPermission { granted -> onShizukuPermissionResult(granted) }
    }

    // =============================================================================================
    // Shizuku / root flags
    // =============================================================================================

    /**
     * Asks for shell access as soon as the app starts, exactly once per process. If the service is
     * not running, [PowerUiState.shizukuAvailable] stays `false` so the UI can ask the user to
     * start Shizuku/Sui.
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
            rootAvailable = granted && ShizukuBridge.serverUid() == 0
            if (granted) {
                requestBatteryReading(force = true)
                restartStatsPipeline(showLoading = true)
            }
            publish()
        }
    }

    /** Reads the Shizuku flags (and whether the service runs as root) without publishing. */
    private fun refreshShizukuFlags() {
        val available = runCatching { ShizukuBridge.isServiceAvailable() }.getOrDefault(false)
        val granted = available && runCatching { ShizukuBridge.hasPermission() }.getOrDefault(false)
        shizukuGranted = granted
        shizukuAvailable = available && !granted
        rootAvailable = granted && ShizukuBridge.serverUid() == 0
    }

    /** Permission revoked / service gone: nothing can be read any more. */
    private fun clearUnavailableData() {
        lastReading = null
        lastSnapshot = null
        latestPowerWatts = null
        lastPowerDump = null
        lastValidPowerWatts = null
        counterReferenceWatts = null
        lockedCurrentDivisor = null
        counterAnchorMicroAmpHours = null
        scenarios = emptyList()
        visualCache = emptyMap()
        foregroundIntervals = emptyMap()
        intervalIndex = emptyList()
        _state.update {
            it.copy(
                snapshot = null,
                summary = PowerSummary(0f, 0L, 0L),
                scenarios = emptyList(),
                loadingScenarios = false,
            )
        }
    }

    // =============================================================================================
    // Heartbeat
    // =============================================================================================

    private fun onTick() {
        val now = System.currentTimeMillis()
        maybeRefreshShizukuState(now)
        requestBatteryReading()
        publish()
    }

    /** Notices Shizuku (or Sui) being started / stopped / revoked. */
    private fun maybeRefreshShizukuState(nowMillis: Long) {
        if (nowMillis - lastPermissionCheckMillis < PERMISSION_CHECK_INTERVAL_MILLIS) return
        lastPermissionCheckMillis = nowMillis

        val wasGranted = shizukuGranted
        refreshShizukuFlags()
        if (wasGranted == shizukuGranted) return

        if (shizukuGranted) {
            requestBatteryReading(force = true)
            restartStatsPipeline(showLoading = false)
        } else {
            clearUnavailableData()
        }
    }

    // =============================================================================================
    // Battery reader (sysfs when root, `dumpsys battery` for shell)
    // =============================================================================================

    /**
     * Single-flight battery read; one exec per tick. A slow exec simply means the next tick re-emits
     * the previous numbers. Nothing is read without the Shizuku permission.
     */
    private fun requestBatteryReading(screenStateOverride: Boolean? = null, force: Boolean = false) {
        if (!monitoring && !force) return
        if (!shizukuGranted) return
        if (batteryJob?.isActive == true) return

        batteryJob = scope.launch {
            val reading = withContext(Dispatchers.IO) { readBattery() }
            if (reading != null) handleBatteryReading(reading, screenStateOverride)
        }
    }

    /**
     * One battery read per tick, picking the most direct current source available:
     * a) sysfs `current_now` (root), b) `BatteryManager.CURRENT_NOW` (public API, no root),
     * c) the OPLUS `Battery current` line of `dumpsys battery`, d) none → the counter anchor is
     * used later as a fallback.
     */
    private suspend fun readBattery(): RawBatteryReading? {
        // Root detection is sticky but re-checked every tick: uid 0 *or* a successful sysfs read
        // means root (Sui can grant root asynchronously), and only repeated failures downgrade.
        val uidIsRoot = ShizukuBridge.serverUid() == 0
        val sysfs = withContext(Dispatchers.IO) {
            val text = ShizukuBridge.exec(SysfsBatterySource.COMMAND)
            SysfsBatterySource.parse(text)
        }
        val sysfsUsable = sysfs != null && sysfs.rawCurrent != null
        if (uidIsRoot || sysfsUsable) {
            rootAvailable = true
            sysfsFailureStreak = 0
        } else if (rootAvailable) {
            sysfsFailureStreak++
            if (sysfsFailureStreak >= ROOT_DOWNGRADE_FAILURES) {
                rootAvailable = false
                sysfsFailureStreak = 0
                lockedCurrentDivisor = null
            }
        } else {
            rootAvailable = false
        }

        // Public API, cheap and synchronous: the preferred source when sysfs is not readable.
        val managerRaw = sampler.readCurrentNowRaw()

        val dump = if (sysfsUsable) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val text = ShizukuBridge.exec("dumpsys battery")
                SystemBatteryStatsSource.parseBatteryDump(text)
            }
        }

        val base = (if (sysfsUsable) sysfs else null) ?: dump?.toRawReading() ?: return null
        val rawCurrent: Long?
        val source: CurrentSource
        when {
            sysfsUsable -> {
                rawCurrent = sysfs.rawCurrent
                source = CurrentSource.SYSFS
            }

            managerRaw != null -> {
                rawCurrent = managerRaw
                source = CurrentSource.BATTERY_MANAGER
            }

            base.rawCurrent != null -> {
                rawCurrent = base.rawCurrent
                source = CurrentSource.DUMPSYS
            }

            else -> {
                rawCurrent = null
                source = CurrentSource.COUNTER
            }
        }
        lastCurrentSource = source
        android.util.Log.d(
            "PowerSource",
            "mode=${if (rootAvailable) "root" else "shell"} source=${source.name.lowercase()} " +
                "raw=$rawCurrent v=${base.voltageVolts} status=${base.status}",
        )
        return base.copy(rawCurrent = rawCurrent)
    }

    /**
     * Reference power from the fuel gauge: `ΔµAh × V / Δt`. The counter is unambiguous, so it is
     * what every raw current reading is calibrated against. Kept for a while so a slowly moving
     * counter does not blank the display.
     */
    private fun updateCounterReference(raw: RawBatteryReading, nowMillis: Long): Float? {
        val counter = raw.chargeCounterMicroAmpHours
        if (counter != null && raw.voltageVolts > 0f) {
            val anchor = counterAnchorMicroAmpHours
            val anchorAt = counterAnchorMillis
            if (anchor == null || anchorAt <= 0L) {
                counterAnchorMicroAmpHours = counter
                counterAnchorMillis = nowMillis
            } else {
                val elapsedMillis = nowMillis - anchorAt
                val deltaMicroAmpHours = abs(counter - anchor)
                if (elapsedMillis >= MIN_COUNTER_WINDOW_MILLIS &&
                    deltaMicroAmpHours >= MIN_COUNTER_DELTA_MICRO_AMP_HOURS
                ) {
                    val hours = elapsedMillis / 3_600_000f
                    if (hours > 0f) {
                        val watts = deltaMicroAmpHours / 1_000_000f * raw.voltageVolts / hours
                        if (watts in PowerMath.MIN_POWER_WATTS..PowerMath.MAX_POWER_WATTS) {
                            counterReferenceWatts = watts
                            counterReferenceAtMillis = nowMillis
                        }
                    }
                    counterAnchorMicroAmpHours = counter
                    counterAnchorMillis = nowMillis
                }
            }
        }
        val reference = counterReferenceWatts ?: return null
        if (nowMillis - counterReferenceAtMillis > COUNTER_REFERENCE_VALIDITY_MILLIS) return null
        return reference
    }

    /**
     * Turns a raw reading into a calibrated one for **this tick**.
     *
     * A power inside the sane range is produced immediately: the charge counter only picks between
     * the unit interpretations (and its absence locks a plausible scale until it can correct it).
     * The counter value itself is only used when there is no raw current at all.
     */
    private fun calibrateReading(raw: RawBatteryReading, referencePowerWatts: Float?): BatteryReading? {
        val discharging = raw.discharging
        var sensorPowerWatts: Float? = null

        val sensorCurrent: Float? = raw.rawCurrent?.let { rawCurrent ->
            CurrentCalibrator.calibrate(
                rawCurrent = rawCurrent,
                voltageVolts = raw.voltageVolts,
                referencePowerWatts = referencePowerWatts,
                discharging = discharging,
                preferredDivisor = lockedCurrentDivisor,
            )?.also { calibration ->
                lockedCurrentDivisor = calibration.divisor
                sensorPowerWatts = calibration.powerWatts
            }?.milliAmps
        }

        val calibratedCurrent: Float = sensorCurrent
            ?: referencePowerWatts?.let {
                CurrentCalibrator.currentFromPower(it, raw.voltageVolts, discharging)
            }
            ?: return null

        val powerWatts = sensorPowerWatts
            ?: (abs(calibratedCurrent) * raw.voltageVolts / 1000f)
        if (powerWatts < PowerMath.MIN_POWER_WATTS || powerWatts > PowerMath.MAX_POWER_WATTS) return null

        return BatteryReading(
            levelPercent = raw.levelPercent,
            voltageVolts = raw.voltageVolts,
            currentMilliAmps = calibratedCurrent,
            temperatureCelsius = raw.temperatureCelsius,
            status = raw.status,
            chargeCounterMicroAmpHours = raw.chargeCounterMicroAmpHours,
            chargeFullMilliAmpHours = raw.chargeFullMilliAmpHours,
        )
    }

    private fun handleBatteryReading(raw: RawBatteryReading, screenStateOverride: Boolean?) {
        val now = System.currentTimeMillis()
        lastRawReading = raw
        lastReadingAtMillis = now
        val screenOn = screenStateOverride ?: lastScreenOn ?: sampler.isScreenOn()
        lastScreenOn = screenOn

        val reference = updateCounterReference(raw, now)
        val reading = calibrateReading(raw, reference)
        if (reading == null) {
            // No trustworthy current yet (right after start-up): keep the last power, do not sample.
            lastSnapshot = buildSnapshot(
                raw = raw,
                screenOn = screenOn,
                nowMillis = now,
                currentMilliAmps = 0f,
                powerWatts = lastValidPowerWatts ?: 0f,
            )
            publish()
            return
        }

        val powerWatts = abs(reading.currentMilliAmps) * reading.voltageVolts / 1000f
        lastReading = reading
        lastValidPowerWatts = powerWatts
        lastSnapshot = buildSnapshot(
            raw = raw,
            screenOn = screenOn,
            nowMillis = now,
            currentMilliAmps = reading.currentMilliAmps,
            powerWatts = powerWatts,
        )
        latestPowerWatts = powerWatts
        handleSampling(reading, screenOn, now)
        publish()
    }

    private fun buildSnapshot(
        raw: RawBatteryReading,
        screenOn: Boolean,
        nowMillis: Long,
        currentMilliAmps: Float,
        powerWatts: Float,
    ): BatterySnapshot = BatterySnapshot(
        timestampMillis = nowMillis,
        levelPercent = raw.levelPercent,
        voltageVolts = raw.voltageVolts,
        temperatureCelsius = raw.temperatureCelsius,
        currentMilliAmps = currentMilliAmps,
        powerWatts = powerWatts,
        charging = raw.charging,
        remainingEnergyWattHours = PowerMath.remainingWattHours(
            chargeCounterMicroAmpHours = raw.chargeCounterMicroAmpHours,
            voltageVolts = raw.voltageVolts,
            levelPercent = raw.levelPercent,
            chargeFullMilliAmpHours = effectiveChargeFullMilliAmpHours(raw),
        ),
        screenOn = screenOn,
    )

    // =============================================================================================
    // Sampling (Scene's Analyser-Power rules)
    // =============================================================================================

    /**
     * Records a sample only while discharging and the screen is on. When the screen turns off one
     * final `screenOn = false` sample is stored (that is Scene's grey chart segment); charging
     * periods record nothing at all.
     */
    private fun handleSampling(reading: BatteryReading, screenOn: Boolean, nowMillis: Long) {
        when {
            reading.charging -> lastSampleWasScreenOn = false

            reading.discharging && screenOn -> recordSample(reading, screenOn = true, nowMillis = nowMillis)

            reading.discharging && !screenOn -> {
                if (lastSampleWasScreenOn) recordSample(reading, screenOn = false, nowMillis = nowMillis)
                lastSampleWasScreenOn = false
            }

            else -> lastSampleWasScreenOn = false
        }
    }

    private fun recordSample(reading: BatteryReading, screenOn: Boolean, nowMillis: Long) {
        if (sessionStartMillis == null) beginSession(reading.levelPercent)
        if (shouldStartNewSession(reading, nowMillis)) beginNewSession(reading.levelPercent)

        if (screenOn) {
            val powerMilliWatts = reading.currentMilliAmps * reading.voltageVolts
            sessionSampleCount++
            sessionPowerMilliWattSum += powerMilliWatts
            // Scene's SQL only filters `package != ''`, so our own package is listed too.
            val packageName = packageAt(nowMillis)
            if (packageName != null) {
                val aggregate = packageAggregates[packageName] ?: PackageAggregate()
                packageAggregates[packageName] = aggregate.add(
                    currentMilliAmps = reading.currentMilliAmps,
                    powerMilliWatts = powerMilliWatts,
                    temperatureCelsius = reading.temperatureCelsius,
                )
            } else {
                unattributedSampleSeen = true
            }
        }

        appendChartPoint(reading, screenOn, nowMillis)
        lastSampleAtMillis = nowMillis
        lastSampleLevel = reading.levelPercent
        lastSampleWasScreenOn = screenOn
        persistSessionIfDue(nowMillis)
    }

    /**
     * Scene starts a new session when the gap since the previous sample exceeds 24 h, when the
     * battery level rose during a gap longer than 30 s, or when the current reading is insane.
     */
    private fun shouldStartNewSession(reading: BatteryReading, nowMillis: Long): Boolean {
        val previous = lastSampleAtMillis
        if (previous <= 0L) return false
        val gap = nowMillis - previous
        if (gap > SESSION_MAX_GAP_MILLIS) return true
        if (gap > SESSION_LEVEL_RISE_GAP_MILLIS && reading.levelPercent > lastSampleLevel) return true
        return abs(reading.currentMilliAmps) > PowerMath.MAX_PLAUSIBLE_CURRENT_MILLIAMPS
    }

    private fun beginNewSession(levelPercent: Int) {
        val now = System.currentTimeMillis()
        archiveCurrentSession(now, levelPercent)
        resetSessionState(now, levelPercent, allowHistorySeed = false)
        persistSession()
    }

    // =============================================================================================
    // Chart
    // =============================================================================================

    /** Appends one chart point; gaps over 10 s become the dotted `MissedRecord*` variants. */
    private fun appendChartPoint(reading: BatteryReading, screenOn: Boolean, nowMillis: Long) {
        val previous = lastChartPointAtMillis
        val missed = previous > 0L && PowerMath.isMissedRecord(nowMillis - previous)
        val style = PowerMath.segmentStyle(screenOn, missed)
        windowSamples.add(
            PowerSample(
                timestampMillis = nowMillis,
                levelPercent = reading.levelPercent,
                voltageVolts = reading.voltageVolts,
                temperatureCelsius = reading.temperatureCelsius,
                currentMilliAmps = reading.currentMilliAmps,
                powerMilliWatts = reading.currentMilliAmps * reading.voltageVolts,
                charging = reading.charging,
                remainingEnergyWattHours = lastSnapshot?.remainingEnergyWattHours,
                screenOn = screenOn,
                style = style,
            ),
        )
        lastChartPointAtMillis = nowMillis
        trimChartWindow(nowMillis)
        rebuildChartSamples()
    }

    private fun trimChartWindow(nowMillis: Long) {
        val threshold = nowMillis - PowerMath.CHART_WINDOW_MILLIS
        while (windowSamples.isNotEmpty() && windowSamples.first().timestampMillis < threshold) {
            windowSamples.removeAt(0)
        }
        while (windowSamples.size > MAX_CHART_SAMPLES) windowSamples.removeAt(0)
    }

    private fun rebuildChartSamples() {
        chartSamples = windowSamples.map {
            ChartSample(it.timestampMillis, it.levelPercent, it.style)
        }
    }

    /** Converts seeded history points; seeded points use the wider gap threshold to stay solid. */
    private fun toChartSamples(
        points: List<SystemBatteryStatsSource.HistoryPoint>,
    ): List<ChartSample> {
        if (points.isEmpty()) return emptyList()
        val samples = ArrayList<ChartSample>(points.size)
        var previousTimestamp = 0L
        for (point in points) {
            val missed = previousTimestamp > 0L &&
                PowerMath.isMissedRecord(
                    point.timestampMillis - previousTimestamp,
                    PowerMath.HISTORY_MISSED_GAP_MILLIS,
                )
            samples += ChartSample(
                timestampMillis = point.timestampMillis,
                levelPercent = point.levelPercent,
                style = PowerMath.segmentStyle(point.screenOn, missed),
            )
            previousTimestamp = point.timestampMillis
        }
        return samples
    }

    private fun mergedChartSamples(): List<ChartSample> {
        val live = chartSamples
        val history = historyChartSamples
        if (history.isEmpty()) return live
        if (live.isEmpty()) return history
        val firstLive = live.first().timestampMillis
        val merged = ArrayList<ChartSample>(history.size + live.size)
        for (sample in history) if (sample.timestampMillis < firstLive) merged += sample
        merged += live
        return merged
    }

    // =============================================================================================
    // Summary / capacity
    // =============================================================================================

    private fun currentSummary(): PowerSummary {
        if (!shizukuGranted || sessionSampleCount <= 0) return PowerSummary(0f, 0L, 0L)
        val averagePowerWatts = (-sessionPowerMilliWattSum / sessionSampleCount / 1000.0)
            .toFloat()
            .takeIf { it.isFinite() } ?: 0f
        val usedMillis = sessionSampleCount.toLong() * PowerMath.TICK_INTERVAL_MILLIS
        return PowerSummary(
            averagePowerWatts = averagePowerWatts,
            usedMillis = usedMillis,
            estimatedRuntimeMillis = PowerMath.estimatedRuntimeMillis(
                remainingWattHours = lastSnapshot?.remainingEnergyWattHours,
                averagePowerWatts = averagePowerWatts,
            ),
        )
    }

    /** Full-pack watt-hours (`chargeFull × 3.86 V`), computed once per process. */
    private fun fullCapacityWattHours(): Float? {
        fullCapacityWattHoursCache?.let { return it }
        val chargeFull = effectiveChargeFullMilliAmpHours(lastRawReading) ?: return null
        val wattHours = PowerMath.fullCapacityWattHours(chargeFull, lastRawReading?.levelPercent ?: 100)
            ?: return null
        fullCapacityWattHoursCache = wattHours
        return wattHours
    }

    /** `chargeFull` from sysfs when root, otherwise the capacity the system learned. */
    private fun effectiveChargeFullMilliAmpHours(reading: RawBatteryReading?): Float? {
        reading?.chargeFullMilliAmpHours?.let {
            chargeFullMilliAmpHours = it
            return it
        }
        chargeFullMilliAmpHours?.let { return it }
        val learned = learnedCapacityMilliAmpHours ?: designCapacityMilliAmpHours ?: return null
        val value = learned.toFloat()
        chargeFullMilliAmpHours = value
        return value
    }

    // =============================================================================================
    // Per-app attribution
    // =============================================================================================

    /**
     * Foreground package at [timestampMillis] according to the batterystats history intervals.
     *
     * The history is a dump: when the user switches apps between two fetches, the new interval does
     * not exist yet. In that case the most recently ended interval is used when it ended within
     * [PACKAGE_FALLBACK_WINDOW_MILLIS] (the app we are most likely looking at), instead of dropping
     * the sample.
     */
    private fun packageAt(timestampMillis: Long): String? {
        var fallback: IntervalEntry? = null
        for (entry in intervalIndex) {
            if (timestampMillis in entry.startMillis until entry.endMillis) return entry.packageName
            if (entry.endMillis <= timestampMillis &&
                timestampMillis - entry.endMillis <= PACKAGE_FALLBACK_WINDOW_MILLIS
            ) {
                if (fallback == null || entry.endMillis > fallback.endMillis) fallback = entry
            }
        }
        return fallback?.packageName
    }

    private fun rebuildIntervalIndex() {
        if (foregroundIntervals.isEmpty()) {
            intervalIndex = emptyList()
            return
        }
        val now = System.currentTimeMillis()
        val entries = ArrayList<IntervalEntry>()
        for ((packageName, intervals) in foregroundIntervals) {
            for ((index, interval) in intervals.withIndex()) {
                val isLast = index == intervals.lastIndex
                val stillOpen = intervalsFetchedAtMillis > 0L &&
                    interval.endMillis >= intervalsFetchedAtMillis - OPEN_INTERVAL_SLACK_MILLIS
                val end = if (isLast && stillOpen) maxOf(interval.endMillis, now) else interval.endMillis
                entries += IntervalEntry(interval.startMillis, end, packageName)
            }
        }
        entries.sortBy { it.startMillis }
        intervalIndex = entries
    }

    private fun rebuildScenariosIfChanged() {
        val built = buildScenarios()
        if (built != scenarios) scenarios = built
    }

    private fun buildScenarios(): List<UsageScenario> {
        val result = ArrayList<UsageScenario>(packageAggregates.size)
        for ((packageName, aggregate) in packageAggregates) {
            if (aggregate.sampleCount <= 0) continue
            val visual = visualCache[packageName] ?: continue
            result += UsageScenario(
                packageName = packageName,
                label = visual.label,
                icon = visual.icon,
                durationMillis = aggregate.sampleCount.toLong() * PowerMath.TICK_INTERVAL_MILLIS,
                averagePowerWatts = aggregate.averagePowerWatts.let { if (it.isFinite()) it else 0f },
                averageTemperatureCelsius = aggregate.averageTemperatureCelsius,
                maxTemperatureCelsius = aggregate.temperatureMax.takeIf { it.isFinite() } ?: 0f,
                averageCurrentMilliAmps = aggregate.averageCurrentMilliAmps,
            )
        }
        return result.sortedByDescending { it.averagePowerWatts }
    }

    // =============================================================================================
    // Stats pipeline (foreground intervals, learned capacity, chart history)
    // =============================================================================================

    private fun restartStatsPipeline(showLoading: Boolean = false) {
        statsJob?.cancel()
        if (showLoading) _state.update { it.copy(loadingScenarios = true) }

        statsJob = scope.launch {
            while (isActive) {
                val startedAt = SystemClock.elapsedRealtime()
                runCatching { refreshStats() }
                _state.update { it.copy(loadingScenarios = false) }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val wait = (MIN_STATS_REFRESH_INTERVAL_MILLIS - elapsed).coerceAtLeast(0L)
                if (wait > 0L) delay(wait)
            }
        }
    }

    private suspend fun refreshStats() {
        refreshShizukuFlags()
        if (!shizukuGranted) {
            clearUnavailableData()
            return
        }

        val fetchedAt = System.currentTimeMillis()
        val historyDue = isHistoryDue(fetchedAt)
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
            var intervals: Map<String, List<TimeInterval>>? = null
            var history: List<SystemBatteryStatsSource.HistoryPoint> = emptyList()
            if (historyDue) {
                intervals = SystemBatteryStatsSource.parseForegroundIntervals(
                    statsText,
                    windowStart,
                    windowEnd,
                    fetchedAt,
                )
                if (historySeedAllowed && windowSamples.isEmpty()) {
                    history = SystemBatteryStatsSource.parseBatteryHistory(
                        statsText,
                        windowStart,
                        windowEnd,
                        fetchedAt,
                    )
                }
            }
            applyStats(powerDump, history, intervals, fetchedAt)
            if (historyDue) lastHistoryFetchMillis = fetchedAt
        }

        val visuals = withContext(Dispatchers.IO) {
            runCatching { packageVisuals.visualsFor(packageAggregates.keys.toList()) }
                .getOrElse { emptyMap() }
        }
        visualCache = visuals
        rebuildScenariosIfChanged()
        publish()
    }

    /**
     * History is read at start-up and hourly. When a package has samples but no interval yet, an
     * extra (rate-limited) fetch happens so its row appears within a minute.
     */
    private fun isHistoryDue(nowMillis: Long): Boolean {
        if (lastHistoryFetchMillis == 0L) return true
        if (nowMillis - lastHistoryFetchMillis >= HISTORY_REFRESH_INTERVAL_MILLIS) return true
        val needsIntervals = unattributedSampleSeen ||
            packageAggregates.keys.any { !foregroundIntervals.containsKey(it) }
        return needsIntervals && nowMillis - lastHistoryFetchMillis >= MIN_HISTORY_RETRY_MILLIS
    }

    private fun applyStats(
        powerDump: SystemBatteryStatsSource.PowerDump?,
        history: List<SystemBatteryStatsSource.HistoryPoint>,
        intervals: Map<String, List<TimeInterval>>?,
        fetchedAtMillis: Long,
    ) {
        if (powerDump != null) {
            powerDump.capacityMilliAmpHours?.let { designCapacityMilliAmpHours = it }
            powerDump.learnedCapacityMilliAmpHours?.let { learnedCapacityMilliAmpHours = it }
            lastPowerDump = powerDump
        }
        if (intervals != null) {
            foregroundIntervals = intervals
            intervalsFetchedAtMillis = fetchedAtMillis
            unattributedSampleSeen = false
            rebuildIntervalIndex()
        }
        if (history.size >= MIN_HISTORY_POINTS) {
            historyChartSamples = toChartSamples(history)
        }
    }

    // =============================================================================================
    // Session lifecycle / persistence
    // =============================================================================================

    private fun beginSession(levelPercent: Int?) {
        resetSessionState(System.currentTimeMillis(), levelPercent, allowHistorySeed = windowSamples.isEmpty())
        persistSession()
    }

    private fun resetSessionState(nowMillis: Long, levelPercent: Int?, allowHistorySeed: Boolean) {
        sessionStartMillis = nowMillis
        startLevelPercent = levelPercent
        sessionSampleCount = 0
        sessionPowerMilliWattSum = 0.0
        packageAggregates.clear()
        windowSamples.clear()
        chartSamples = emptyList()
        historyChartSamples = emptyList()
        historySeedAllowed = allowHistorySeed
        lastChartPointAtMillis = 0L
        lastSampleAtMillis = 0L
        lastSampleLevel = -1
        lastSampleWasScreenOn = false
        lastSamplesPersistedAtMillis = 0L
    }

    private fun archiveCurrentSession(nowMillis: Long, endLevelPercent: Int?) {
        val start = sessionStartMillis ?: return
        if (sessionSampleCount <= 0) return
        val averagePowerWatts = (-sessionPowerMilliWattSum / sessionSampleCount / 1000.0).toFloat()
        val durationMillis = sessionSampleCount.toLong() * PowerMath.TICK_INTERVAL_MILLIS
        val record = SessionRecord(
            startedAtMillis = start,
            durationMillis = durationMillis,
            averagePowerWatts = averagePowerWatts.coerceAtLeast(0f),
            energyWattHours = averagePowerWatts.coerceAtLeast(0f) * durationMillis / 3_600_000f,
            startLevelPercent = startLevelPercent ?: endLevelPercent ?: 100,
            endLevelPercent = endLevelPercent ?: startLevelPercent ?: 100,
        )
        sessionHistory = (listOf(record) + sessionHistory).take(MAX_SESSION_HISTORY)
        store.saveHistory(sessionHistory)
    }

    private fun restoreSession() {
        val stored = store.loadSession() ?: return
        sessionStartMillis = stored.startMillis
        startLevelPercent = stored.startLevelPercent
        sessionSampleCount = stored.sampleCount
        sessionPowerMilliWattSum = stored.powerMilliWattSum
        packageAggregates.putAll(stored.packages)
        lastSampleAtMillis = stored.lastSampleAtMillis
        lastSampleLevel = stored.lastSampleLevel

        val threshold = System.currentTimeMillis() - PowerMath.CHART_WINDOW_MILLIS
        for (sample in store.loadSamples()) {
            if (sample.timestampMillis >= threshold) windowSamples.add(sample)
        }
        lastChartPointAtMillis = windowSamples.lastOrNull()?.timestampMillis ?: 0L
        rebuildChartSamples()
        historySeedAllowed = windowSamples.isEmpty()
    }

    private fun persistSession() {
        store.saveSession(
            sessionStartMillis?.let { start ->
                StoredSession(
                    startMillis = start,
                    startLevelPercent = startLevelPercent,
                    sampleCount = sessionSampleCount,
                    powerMilliWattSum = sessionPowerMilliWattSum,
                    lastSampleAtMillis = lastSampleAtMillis,
                    lastSampleLevel = lastSampleLevel,
                    packages = packageAggregates,
                )
            },
        )
        store.saveSamples(windowSamples)
        store.saveHistory(sessionHistory)
    }

    private fun persistSessionIfDue(nowMillis: Long) {
        if (nowMillis - lastSamplesPersistedAtMillis < SESSION_PERSIST_INTERVAL_MILLIS) return
        lastSamplesPersistedAtMillis = nowMillis
        persistSession()
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
                rootAvailable = rootAvailable,
            )
        }
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

    private data class IntervalEntry(
        val startMillis: Long,
        val endMillis: Long,
        val packageName: String,
    )

    private companion object {
        /** `dumpsys batterystats --charged` cadence; the 3 s tick re-emits cached values in between. */
        const val MIN_STATS_REFRESH_INTERVAL_MILLIS = 6_000L

        /** `--history` (foreground intervals + chart seed) cadence. */
        const val HISTORY_REFRESH_INTERVAL_MILLIS = 60L * 60_000L

        /** Extra history fetch when a sampled package has no interval yet (rate limit). */
        const val MIN_HISTORY_RETRY_MILLIS = 60_000L

        /** A `--history` stamp a few seconds ahead of `now` is normal (clock skew). */
        const val HISTORY_CLOCK_SKEW_MILLIS = 5_000L

        /** An interval that ends this close to the history fetch was still open. */
        const val OPEN_INTERVAL_SLACK_MILLIS = 15_000L

        /** Attribute a sample to the last foreground app if it left at most this long ago. */
        const val PACKAGE_FALLBACK_WINDOW_MILLIS = 30_000L

        /** Fewer reconstructed points than this means the history was missing/rotated away. */
        const val MIN_HISTORY_POINTS = 2

        /** Session persistence cadence. */
        const val SESSION_PERSIST_INTERVAL_MILLIS = 60_000L

        /** Periods that make Scene start a new session. */
        const val SESSION_MAX_GAP_MILLIS = 24L * 60L * 60_000L
        const val SESSION_LEVEL_RISE_GAP_MILLIS = 30_000L

        /** Counter reference window: one tick, so the calibration can follow quickly. */
        const val MIN_COUNTER_WINDOW_MILLIS = 3_000L

        /** Only a full 1 mAh step is meaningful (the gauge resolution). */
        const val MIN_COUNTER_DELTA_MICRO_AMP_HOURS = 1_000L

        /** A counter reference older than this is stale and no longer used for calibration. */
        const val COUNTER_REFERENCE_VALIDITY_MILLIS = 120_000L

        /**
         * Root is sticky: a single failed sysfs read (service restart, SELinux hiccup) must not flip
         * the power source back and forth.
         */
        const val ROOT_DOWNGRADE_FAILURES = 5

        /** Shizuku/root re-check runs on every 3 s tick (root can appear asynchronously). */
        const val PERMISSION_CHECK_INTERVAL_MILLIS = 1_000L

        /** Give `dumpsys batterystats --reset` a moment before reading the counters back. */
        const val SCENARIO_REFRESH_AFTER_RESET_MILLIS = 800L

        /** Finished sessions kept by "清理数据" / automatic session resets. */
        const val MAX_SESSION_HISTORY = 20

        /** Hard cap on chart points (the 45 min window holds ≤ 900 at 3 s). */
        const val MAX_CHART_SAMPLES = 1_000
    }
}
