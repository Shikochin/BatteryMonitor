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
import dev.shiko.batterymonitor.model.DaemonState
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The single source of truth for the "耗电统计" screen.
 *
 * The maths are aligned with Scene 9.4.18 (`Analyser-Power` + its SQL queries):
 *
 *  - battery/chart samples are recorded every 3 s throughout discharge; charging periods record nothing,
 *  - on-screen and screen-off power/time summaries are tracked separately,
 *  - each usage duration is elapsed discharging time in that screen state,
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
    private var sessionUsedMillis: Long = 0L
    /** Σ(power_mW × elapsed_ms), negative while discharging. */
    private var sessionWeightedPowerMilliWattMillisSum: Double = 0.0
    private var screenOnUsedMillis: Long = 0L
    private var screenOnWeightedPowerMilliWattMillisSum: Double = 0.0
    private var screenOffUsedMillis: Long = 0L
    private var screenOffWeightedPowerMilliWattMillisSum: Double = 0.0
    private val packageAggregates = LinkedHashMap<String, PackageAggregate>()

    private var lastSampleAtMillis: Long = 0L
    private var lastSampleLevel: Int = -1
    private var lastSampleWasScreenOn: Boolean = false
    private var lastSampleWasDischarging: Boolean = false
    private var lastSamplePowerMilliWatts: Float = 0f

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

    // --- root sampling daemon (keeps collecting while the app process is frozen) -----------------
    private val daemon = RootMonitorDaemon(appContext)
    /** Serializes probe/start/stop so two operations can never race the process table. */
    private val daemonOps = Mutex()
    /** Last state verified by a probe; never set optimistically. */
    private var daemonState: DaemonState = DaemonState.UNKNOWN
    private var daemonRestartInFlight: Boolean = false
    private var daemonOffset: Long = store.loadDaemonOffset()
    private var lastDaemonSampleAt: Long = store.loadDaemonLastSample()
    private var lastDaemonProbeAt: Long = 0L
    private var lastDaemonStartAttemptAt: Long = 0L
    /** Last heartbeat value observed, used to detect a live-but-wedged sampler. */
    private var lastDaemonHeartbeatMillis: Long = 0L
    /** First time the current heartbeat value was seen; reset whenever it advances. */
    private var daemonHeartbeatStuckAt: Long = 0L
    /** Rate limit for heartbeat-driven restarts. */
    private var lastDaemonRecoveryAt: Long = 0L
    private var daemonIngestJob: Job? = null
    private var daemonMaintainJob: Job? = null
    private var daemonStartJob: Job? = null
    private var daemonRestartJob: Job? = null

    /** `true` only while a probe has verified exactly one healthy sampler. */
    private val daemonActive: Boolean get() = daemonState == DaemonState.RUNNING

    /** Screen-on samples that could not be attributed yet; retried after the next `--history` fetch. */
    private val pendingAttribution = ArrayDeque<PendingAttribution>()

    // Daemon-side current calibration, kept apart from the live path's state.
    private var daemonDivisor: Float? = null
    private var daemonCounterAnchorMicroAmpHours: Long? = null
    private var daemonCounterAnchorAtMillis: Long = 0L
    private var daemonReferenceWatts: Float? = null
    private var daemonReferenceAtMillis: Long = 0L

    // --- per-app presentation ---------------------------------------------------------------------
    private var scenarios: List<UsageScenario> = emptyList()
    private var visualCache: Map<String, PackageVisuals.AppVisual> = emptyMap()

    // --- lifecycle --------------------------------------------------------------------------------
    private var monitoring: Boolean = false
    private var receiverRegistered: Boolean = false
    private var tickJob: Job? = null
    private var batteryJob: Job? = null
    private var statsJob: Job? = null
    private var visualsJob: Job? = null

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
            // Coming back from the background is exactly when a dead daemon used to be missed:
            // force a real probe instead of trusting the last cached state.
            maintainDaemon(force = true)
            return
        }
        monitoring = true
        registerBatteryReceiver()

        if (sessionStartMillis == null) beginSession(lastReading?.levelPercent ?: lastSnapshot?.levelPercent)

        lastPermissionCheckMillis = System.currentTimeMillis()
        refreshShizukuFlags()
        maybeRequestShizukuPermissionUpFront()
        maintainDaemon(force = true)
        DaemonWatchdog.schedule(appContext)

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

    /**
     * Called when the activity comes back to the foreground. Re-checks Shizuku and forces a real
     * daemon probe, so a sampler that died while the app was frozen is restarted immediately and
     * the footer never shows a stale "running".
     */
    fun onAppForeground() {
        refreshShizukuState()
        // A quick round-trip (permission dialog, recents) keeps the fresh state; after a real
        // absence the old snapshot is worthless, so show "unknown" until the probe lands rather
        // than a potentially hours-old "running".
        if (System.currentTimeMillis() - lastDaemonProbeAt > DAEMON_PROBE_FRESH_MILLIS) {
            updateDaemonState(DaemonState.UNKNOWN)
        }
        maintainDaemon(force = true)
    }

    /**
     * Manual "restart sampler" action: kills any running daemon and starts a fresh one.
     *
     * Useful after a reboot (the sampler cannot survive it) or when the user suspects the sampler
     * died — the summary card and the scenario list start moving again as soon as it is back.
     */
    fun restartDaemon() = restartDaemonInternal("manual", showUi = true)

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
        // Everything the daemon wrote before this reset belongs to the wiped session: skip it.
        lastDaemonSampleAt = System.currentTimeMillis()
        store.saveDaemonLastSample(lastDaemonSampleAt)
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
                screenOffSummary = PowerSummary(0f, 0L, 0L),
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
                maintainDaemon(force = true)
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
                maintainDaemon(force = true)
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
        // The root process may still be alive, but without Shizuku there is no way to verify it:
        // "unknown" is the only honest answer (never a stale "running").
        daemonState = DaemonState.UNKNOWN
        lastDaemonHeartbeatMillis = 0L
        daemonHeartbeatStuckAt = 0L
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
                screenOffSummary = PowerSummary(0f, 0L, 0L),
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
        // Probes the real process table; starts / replaces the sampler when it is not the single
        // healthy instance. Internally throttled, so this is cheap on every 3 s tick.
        maintainDaemon()
        // Ingest on every tick, not only on the 10 s liveness probe: the daemon writes a sample
        // every 3 s, and the chart / summary must move at the same cadence.
        if (daemonActive) requestDaemonIngest()
        // Keep the "still open" foreground interval stretching to now: it is rebuilt from the last
        // `--history` dump, so without this the app on screen stops matching ~30 s after the dump
        // and its samples would be dropped.
        if (intervalIndex.isNotEmpty()) rebuildIntervalIndex()
        // The scenario list is pure aggregation over the in-memory session samples, so it is
        // recomputed on every tick (3 s) like the rest of the screen — no extra shell traffic.
        ensureVisualsForAggregates()
        rebuildScenariosIfChanged()
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
            // The daemon dies with the Shizuku server; re-arm it as soon as the binder is back.
            maintainDaemon(force = true)
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
            if (!raw.discharging) {
                accumulateDischargeUntil(now, maxElapsedMillis = PowerMath.TICK_INTERVAL_MILLIS)
                lastSampleWasScreenOn = false
                lastSampleWasDischarging = false
                persistSessionIfDue(now)
            }
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
        // With the root daemon running the session samples come from the daemon (it keeps collecting
        // while this process is frozen); the live read now only feeds the telemetry header.
        if (!daemonActive) handleSampling(reading, screenOn, now)
        ensureVisualsForAggregates()
        rebuildScenariosIfChanged()
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
     * Records battery and chart samples throughout discharge. Package aggregates remain
     * screen-on only; charging periods do not contribute to discharge time or average power.
     */
    private fun handleSampling(
        reading: BatteryReading,
        screenOn: Boolean,
        nowMillis: Long,
        attributedPackage: String? = null,
    ) {
        when {
            reading.charging -> {
                accumulateDischargeUntil(
                    nowMillis,
                    maxElapsedMillis = PowerMath.TICK_INTERVAL_MILLIS,
                )
                lastSampleWasScreenOn = false
                lastSampleWasDischarging = false
                persistSessionIfDue(nowMillis)
            }

            reading.discharging && screenOn ->
                recordSample(reading, screenOn = true, nowMillis = nowMillis, attributedPackage = attributedPackage)

            reading.discharging && !screenOn -> {
                val screenOffSampleDue = !lastSampleWasDischarging ||
                    nowMillis - lastSampleAtMillis >= PowerMath.SCREEN_OFF_TICK_INTERVAL_MILLIS
                if (screenOffSampleDue) recordSample(reading, screenOn = false, nowMillis = nowMillis)
            }

            else -> {
                if (lastSampleWasDischarging) {
                    accumulateDischargeUntil(
                        nowMillis,
                        maxElapsedMillis = PowerMath.TICK_INTERVAL_MILLIS,
                    )
                }
                lastSampleWasScreenOn = false
                lastSampleWasDischarging = false
            }
        }
    }

    private fun recordSample(
        reading: BatteryReading,
        screenOn: Boolean,
        nowMillis: Long,
        attributedPackage: String? = null,
    ) {
        if (sessionStartMillis == null) beginSession(reading.levelPercent)
        if (shouldStartNewSession(reading, nowMillis)) beginNewSession(reading.levelPercent)

        val powerMilliWatts = reading.currentMilliAmps * reading.voltageVolts
        accumulateDischargeUntil(nowMillis)

        if (screenOn) {
            sessionSampleCount++
            sessionPowerMilliWattSum += powerMilliWatts
            // Scene's SQL only filters `package != ''`, so our own package is listed too.
            val packageName = attributedPackage ?: packageAt(nowMillis)
            if (packageName != null) {
                val aggregate = packageAggregates[packageName] ?: PackageAggregate()
                packageAggregates[packageName] = aggregate.add(
                    currentMilliAmps = reading.currentMilliAmps,
                    powerMilliWatts = powerMilliWatts,
                    temperatureCelsius = reading.temperatureCelsius,
                )
            } else {
                unattributedSampleSeen = true
                rememberPendingAttribution(nowMillis, powerMilliWatts, reading)
            }
        }

        appendChartPoint(reading, screenOn, nowMillis, attributedPackage.takeIf { screenOn })
        lastSampleAtMillis = nowMillis
        lastSampleLevel = reading.levelPercent
        lastSampleWasScreenOn = screenOn
        lastSampleWasDischarging = true
        lastSamplePowerMilliWatts = powerMilliWatts
        persistSessionIfDue(nowMillis)
    }

    private fun accumulateDischargeUntil(nowMillis: Long, maxElapsedMillis: Long? = null) {
        if (!lastSampleWasDischarging || lastSampleAtMillis <= 0L) return
        val elapsedMillis = (nowMillis - lastSampleAtMillis).coerceAtLeast(0L)
            .let { maxElapsedMillis?.let(it::coerceAtMost) ?: it }
        sessionUsedMillis += elapsedMillis
        sessionWeightedPowerMilliWattMillisSum += lastSamplePowerMilliWatts * elapsedMillis
        if (lastSampleWasScreenOn) {
            screenOnUsedMillis += elapsedMillis
            screenOnWeightedPowerMilliWattMillisSum += lastSamplePowerMilliWatts * elapsedMillis
        } else {
            screenOffUsedMillis += elapsedMillis
            screenOffWeightedPowerMilliWattMillisSum += lastSamplePowerMilliWatts * elapsedMillis
        }
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

    /** Appends a raw chart point; interval styles are derived from adjacent timestamps below. */
    private fun appendChartPoint(
        reading: BatteryReading,
        screenOn: Boolean,
        nowMillis: Long,
        packageName: String? = null,
    ) {
        val style = PowerMath.segmentStyle(screenOn, missedRecord = false)
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
                packageName = packageName,
            ),
        )
        lastChartPointAtMillis = nowMillis
        trimChartWindow(nowMillis)
        rebuildChartSamples()
    }

    /**
     * Keeps [PowerMath.CHART_RETENTION_MILLIS] of history so the chart can be dragged past its
     * 45 min viewport. Samples older than the viewport are thinned to one per 30 s: 12 h of that
     * fits comfortably in memory and in SharedPreferences.
     */
    private fun trimChartWindow(nowMillis: Long) {
        val retentionThreshold = nowMillis - PowerMath.CHART_RETENTION_MILLIS
        while (windowSamples.isNotEmpty() && windowSamples.first().timestampMillis < retentionThreshold) {
            windowSamples.removeAt(0)
        }
        downsampleBeyondDetailWindow(nowMillis)
        while (windowSamples.size > MAX_CHART_SAMPLES) windowSamples.removeAt(0)
    }

    /** One sample per [PowerMath.CHART_OLD_SAMPLE_SPACING_MILLIS] outside the detailed viewport. */
    private fun downsampleBeyondDetailWindow(nowMillis: Long) {
        val detailThreshold = nowMillis - PowerMath.CHART_WINDOW_MILLIS
        var index = 0
        var previousKeptAt = Long.MIN_VALUE
        while (index < windowSamples.size) {
            val timestamp = windowSamples[index].timestampMillis
            if (timestamp >= detailThreshold) break
            if (previousKeptAt != Long.MIN_VALUE &&
                timestamp - previousKeptAt < PowerMath.CHART_OLD_SAMPLE_SPACING_MILLIS
            ) {
                windowSamples.removeAt(index)
            } else {
                previousKeptAt = timestamp
                index++
            }
        }
    }

    private fun rebuildChartSamples() {
        chartSamples = windowSamples.mapIndexed { index, sample ->
            val next = windowSamples.getOrNull(index + 1)
            val missed = next != null && PowerMath.isMissedRecord(next.timestampMillis - sample.timestampMillis)
            ChartSample(
                timestampMillis = sample.timestampMillis,
                levelPercent = sample.levelPercent,
                style = PowerMath.segmentStyle(sample.screenOn, missed),
                packageName = sample.packageName,
                powerMilliWatts = sample.powerMilliWatts,
            )
        }
    }

    /** Converts seeded history points; seeded points use the wider gap threshold to stay solid. */
    private fun toChartSamples(
        points: List<SystemBatteryStatsSource.HistoryPoint>,
    ): List<ChartSample> {
        if (points.isEmpty()) return emptyList()
        val samples = ArrayList<ChartSample>(points.size)
        for ((index, point) in points.withIndex()) {
            val next = points.getOrNull(index + 1)
            val missed = next != null &&
                PowerMath.isMissedRecord(next.timestampMillis - point.timestampMillis, PowerMath.HISTORY_MISSED_GAP_MILLIS)
            samples += ChartSample(
                timestampMillis = point.timestampMillis,
                levelPercent = point.levelPercent,
                style = PowerMath.segmentStyle(point.screenOn, missed),
            )
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

    private fun currentSummary(screenOn: Boolean): PowerSummary {
        if (!shizukuGranted) return PowerSummary(0f, 0L, 0L)
        val projectedMillis = if (
            lastSampleWasDischarging && lastSampleWasScreenOn == screenOn && lastSampleAtMillis > 0L
        ) {
            (System.currentTimeMillis() - lastSampleAtMillis).coerceAtLeast(0L)
        } else {
            0L
        }
        val usedMillis = (if (screenOn) screenOnUsedMillis else screenOffUsedMillis) + projectedMillis
        val weightedPowerSum = (if (screenOn) screenOnWeightedPowerMilliWattMillisSum
        else screenOffWeightedPowerMilliWattMillisSum) + lastSamplePowerMilliWatts * projectedMillis
        val averagePowerWatts = PowerMath.averageDischargePowerWatts(weightedPowerSum, usedMillis)
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
     * Loads label/icon for packages that just appeared in the aggregates.
     *
     * Icons are cached inside [PackageVisuals], so this only touches `PackageManager` once per new
     * package — never once per tick, which would thrash the GC.
     */
    private fun ensureVisualsForAggregates() {
        val missing = packageAggregates.keys.filterNot(visualCache::containsKey)
        if (missing.isEmpty()) return
        if (visualsJob?.isActive == true) return
        visualsJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { packageVisuals.visualsFor(missing) }.getOrElse { emptyMap() }
            }
            if (loaded.isEmpty()) return@launch
            visualCache = visualCache + loaded
            rebuildScenariosIfChanged()
            publish()
        }
    }

    /**
     * Foreground package at [timestampMillis] according to the batterystats history intervals.
     *
     * The history is a dump: when the user switches apps between two fetches, the new interval does
     * not exist yet. In that case the most recently ended interval is used when it ended within
     * [PACKAGE_FALLBACK_WINDOW_MILLIS] (the app we are most likely looking at), instead of dropping
     * the sample.
     */
    private fun packageAt(timestampMillis: Long): String? {
        var best: IntervalEntry? = null
        var fallback: IntervalEntry? = null
        for (entry in intervalIndex) {
            if (timestampMillis in entry.startMillis until entry.endMillis) {
                // Intervals of "still open" apps are extended to now and may overlap; the one that
                // started last is the app that actually came to the foreground most recently.
                if (best == null || entry.startMillis >= best.startMillis) best = entry
            }
            if (entry.endMillis <= timestampMillis &&
                timestampMillis - entry.endMillis <= PACKAGE_FALLBACK_WINDOW_MILLIS
            ) {
                if (fallback == null || entry.endMillis > fallback.endMillis) fallback = entry
            }
        }
        return best?.packageName ?: fallback?.packageName
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
    // Root sampling daemon
    // =============================================================================================

    /**
     * Probes the real process table and reconciles the daemon.
     *
     * This is the only place that decides whether the sampler is running: every call asks Shizuku
     * for the actual process list, so a dead daemon can never keep displaying "running" just
     * because a start was requested earlier. Probes are single-flight and throttled; [force] is
     * used when the app returns to the foreground or Shizuku comes back.
     */
    private fun maintainDaemon(force: Boolean = false) {
        if (!monitoring) return
        if (daemonRestartInFlight) return
        if (daemonMaintainJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!force && now - lastDaemonProbeAt < DAEMON_PROBE_INTERVAL_MILLIS) return
        lastDaemonProbeAt = now

        daemonMaintainJob = scope.launch {
            val status = withContext(Dispatchers.IO) {
                daemonOps.withLock { runCatching { daemon.status() }.getOrNull() }
            }
            if (status == null) {
                updateDaemonState(DaemonState.UNKNOWN)
                return@launch
            }
            updateDaemonState(status.state)
            when (status.state) {
                DaemonState.RUNNING -> {
                    if (status.outdated) {
                        // The app was updated while a sampler from the previous build kept running.
                        // Replace it once so the new script (and its log format) takes effect.
                        ensureDaemonStarted("script upgrade", force = true)
                    } else {
                        trackDaemonHeartbeat(status, now)
                        requestDaemonIngest()
                    }
                }

                DaemonState.STOPPED -> {
                    daemonHeartbeatStuckAt = 0L
                    ensureDaemonStarted("probe")
                }

                DaemonState.UNKNOWN -> Unit
            }
        }
    }

    /** Starts the detached root sampler when it is not the single healthy instance. */
    private fun ensureDaemonStarted(reason: String, force: Boolean = false) {
        if (!shizukuGranted) return
        if (!rootAvailable) return
        if (daemonRestartInFlight) return
        // One attempt at a time: two concurrent spawns would race the process table.
        if (daemonStartJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!force && now - lastDaemonStartAttemptAt < DAEMON_START_RETRY_MILLIS) return
        lastDaemonStartAttemptAt = now

        daemonStartJob = scope.launch {
            val started = withContext(Dispatchers.IO) {
                daemonOps.withLock { runCatching { daemon.ensureRunning() }.getOrDefault(false) }
            }
            lastDaemonProbeAt = System.currentTimeMillis()
            daemonState = if (started) DaemonState.RUNNING else DaemonState.STOPPED
            android.util.Log.d("BatteryDaemon", "ensureRunning($reason): running=$started")
            if (started) requestDaemonIngest()
            publish()
        }
    }

    /**
     * A live process can still be wedged (a stuck `dumpsys` never returns to the loop). The
     * heartbeat is the difference between "process exists" and "actually sampling".
     *
     * Only judged while the screen is on: in deep sleep the whole device is suspended, so a stale
     * heartbeat there is normal and must not trigger a restart.
     */
    private fun trackDaemonHeartbeat(status: DaemonStatus, nowMillis: Long) {
        val heartbeat = status.heartbeatMillis ?: return
        if (heartbeat != lastDaemonHeartbeatMillis) {
            lastDaemonHeartbeatMillis = heartbeat
            daemonHeartbeatStuckAt = nowMillis
            return
        }
        if (daemonHeartbeatStuckAt == 0L) daemonHeartbeatStuckAt = nowMillis
        if (lastScreenOn != true) return
        if (nowMillis - daemonHeartbeatStuckAt < DAEMON_HEARTBEAT_STALL_MILLIS) return
        if (nowMillis - lastDaemonRecoveryAt < DAEMON_RECOVERY_INTERVAL_MILLIS) return

        lastDaemonRecoveryAt = nowMillis
        daemonHeartbeatStuckAt = nowMillis
        android.util.Log.w(
            "BatteryDaemon",
            "heartbeat stuck ${(nowMillis - heartbeat) / 1000}s, restarting sampler",
        )
        restartDaemonInternal("heartbeat stuck")
    }

    private fun updateDaemonState(state: DaemonState) {
        if (daemonState == state) return
        daemonState = state
        publish()
    }

    /** Single restart path for the manual action and the heartbeat watchdog. */
    private fun restartDaemonInternal(reason: String, showUi: Boolean = false) {
        if (daemonRestartJob?.isActive == true) return
        daemonRestartJob = scope.launch {
            daemonRestartInFlight = true
            if (showUi) _state.update { it.copy(daemonRestarting = true) }

            val started = withContext(Dispatchers.IO) {
                daemonOps.withLock { runCatching { daemon.restart() }.getOrDefault(false) }
            }
            val now = System.currentTimeMillis()
            lastDaemonStartAttemptAt = now
            lastDaemonProbeAt = now
            daemonState = if (started) DaemonState.RUNNING else DaemonState.STOPPED
            if (started) {
                lastDaemonHeartbeatMillis = 0L
                daemonHeartbeatStuckAt = 0L
                lastDaemonRecoveryAt = 0L
                requestDaemonIngest()
            }
            daemonRestartInFlight = false
            if (showUi) _state.update { it.copy(daemonRestarting = false) }
            android.util.Log.w("BatteryDaemon", "restart($reason): running=$started")
            publish()
        }
    }

    /** Reads the samples the daemon wrote since the last ingest and folds them into the session. */
    private fun requestDaemonIngest() {
        if (!daemonActive) return
        if (daemonIngestJob?.isActive == true) return
        daemonIngestJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                val batch = runCatching { daemon.readNew(daemonOffset) }.getOrNull()
                    ?: return@withContext null
                // The sample lines no longer repeat the rated capacity; it lives in the state file.
                val rated = runCatching { daemon.chargeFullMilliAmpHours() }.getOrNull()
                batch to rated
            } ?: return@launch
            val (batch, ratedCapacity) = result
            if (batch.samples.isEmpty() && batch.nextOffset == daemonOffset) return@launch

            ratedCapacity?.let { chargeFullMilliAmpHours = it }

            daemonOffset = batch.nextOffset
            store.saveDaemonOffset(batch.nextOffset)

            var applied = 0
            for (sample in batch.samples) {
                if (sample.timestampMillis <= lastDaemonSampleAt) continue
                applyDaemonSample(sample)
                applied++
            }
            if (applied > 0) {
                store.saveDaemonLastSample(lastDaemonSampleAt)
                ensureVisualsForAggregates()
                rebuildScenariosIfChanged()
                persistSessionIfDue(System.currentTimeMillis())
                publish()
            }
        }
    }

    /**
     * Turns one daemon sample into the same pipeline the live reader uses.
     *
     * The daemon already knows the foreground package, so a background sample can be attributed
     * without waiting for the next `dumpsys batterystats --history` dump.
     */
    private fun applyDaemonSample(sample: DaemonSample) {
        lastDaemonSampleAt = maxOf(lastDaemonSampleAt, sample.timestampMillis)
        val start = sessionStartMillis
        if (start != null && sample.timestampMillis < start) return

        val raw = RawBatteryReading(
            levelPercent = sample.levelPercent,
            voltageVolts = sample.voltageVolts,
            rawCurrent = sample.rawCurrent,
            temperatureCelsius = sample.temperatureCelsius,
            status = sample.status,
            chargeCounterMicroAmpHours = sample.chargeCounterMicroAmpHours,
            chargeFullMilliAmpHours = sample.chargeFullMilliAmpHours,
        )
        if (!raw.discharging) {
            accumulateDischargeUntil(
                sample.timestampMillis,
                maxElapsedMillis = PowerMath.TICK_INTERVAL_MILLIS,
            )
            lastSampleWasScreenOn = false
            lastSampleWasDischarging = false
        }
        val rawCurrent = raw.rawCurrent ?: return
        val calibration = CurrentCalibrator.calibrate(
            rawCurrent = rawCurrent,
            voltageVolts = raw.voltageVolts,
            referencePowerWatts = daemonReferencePower(sample),
            discharging = raw.discharging,
            preferredDivisor = daemonDivisor,
        ) ?: return
        daemonDivisor = calibration.divisor

        val reading = BatteryReading(
            levelPercent = raw.levelPercent,
            voltageVolts = raw.voltageVolts,
            currentMilliAmps = calibration.milliAmps,
            temperatureCelsius = raw.temperatureCelsius,
            status = raw.status,
            chargeCounterMicroAmpHours = raw.chargeCounterMicroAmpHours,
            chargeFullMilliAmpHours = raw.chargeFullMilliAmpHours,
        )
        handleSampling(
            reading = reading,
            screenOn = sample.screenOn,
            nowMillis = sample.timestampMillis,
            attributedPackage = sample.foregroundPackage,
        )
    }

    /** Reference power from the charge counter over a window long enough for its 1 mAh resolution. */
    private fun daemonReferencePower(sample: DaemonSample): Float? {
        val counter = sample.chargeCounterMicroAmpHours
        if (counter != null && sample.voltageVolts > 0f) {
            val anchor = daemonCounterAnchorMicroAmpHours
            if (anchor == null) {
                daemonCounterAnchorMicroAmpHours = counter
                daemonCounterAnchorAtMillis = sample.timestampMillis
            } else {
                val elapsed = sample.timestampMillis - daemonCounterAnchorAtMillis
                val delta = abs(counter - anchor)
                if (elapsed >= DAEMON_COUNTER_WINDOW_MILLIS &&
                    delta >= MIN_COUNTER_DELTA_MICRO_AMP_HOURS
                ) {
                    val hours = elapsed / 3_600_000f
                    if (hours > 0f) {
                        val watts = delta / 1_000_000f * sample.voltageVolts / hours
                        if (watts in PowerMath.MIN_POWER_WATTS..PowerMath.MAX_POWER_WATTS) {
                            daemonReferenceWatts = watts
                            daemonReferenceAtMillis = sample.timestampMillis
                        }
                    }
                    daemonCounterAnchorMicroAmpHours = counter
                    daemonCounterAnchorAtMillis = sample.timestampMillis
                }
            }
        }
        val reference = daemonReferenceWatts ?: return null
        if (sample.timestampMillis - daemonReferenceAtMillis >
            COUNTER_REFERENCE_VALIDITY_MILLIS * 2
        ) {
            return null
        }
        return reference
    }

    private fun rememberPendingAttribution(
        timestampMillis: Long,
        powerMilliWatts: Float,
        reading: BatteryReading,
    ) {
        if (pendingAttribution.size >= MAX_PENDING_ATTRIBUTIONS) pendingAttribution.removeFirst()
        pendingAttribution.addLast(
            PendingAttribution(
                timestampMillis = timestampMillis,
                powerMilliWatts = powerMilliWatts,
                currentMilliAmps = reading.currentMilliAmps,
                temperatureCelsius = reading.temperatureCelsius,
            ),
        )
        unattributedSampleSeen = true
    }

    /** Re-attributes samples that had no interval when they were recorded (after a history dump). */
    private fun retryPendingAttribution() {
        if (pendingAttribution.isEmpty()) {
            unattributedSampleSeen = false
            return
        }
        val iterator = pendingAttribution.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            val packageName = packageAt(pending.timestampMillis) ?: continue
            val aggregate = packageAggregates[packageName] ?: PackageAggregate()
            packageAggregates[packageName] = aggregate.add(
                currentMilliAmps = pending.currentMilliAmps,
                powerMilliWatts = pending.powerMilliWatts,
                temperatureCelsius = pending.temperatureCelsius,
            )
            iterator.remove()
        }
        unattributedSampleSeen = pendingAttribution.isNotEmpty()
    }

    /**
     * Assigns a foreground package to already-recorded chart points that have none.
     *
     * Samples recorded by an older build (or restored from persistence before the first history
     * dump) carry no package, so their app icons would be missing from the curve.
     */
    private fun backfillChartPackages() {
        if (intervalIndex.isEmpty() || windowSamples.isEmpty()) return
        var changed = false
        for (index in windowSamples.indices) {
            val sample = windowSamples[index]
            if (sample.packageName != null || !sample.screenOn) continue
            val packageName = packageAt(sample.timestampMillis) ?: continue
            windowSamples[index] = sample.copy(packageName = packageName)
            changed = true
        }
        if (changed) rebuildChartSamples()
    }

    /** One screen-on sample waiting for a foreground interval to attribute it to. */
    private data class PendingAttribution(
        val timestampMillis: Long,
        val powerMilliWatts: Float,
        val currentMilliAmps: Float,
        val temperatureCelsius: Float,
    )

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
            val chartWindowStart = fetchedAt - PowerMath.CHART_WINDOW_MILLIS
            val windowEnd = fetchedAt + HISTORY_CLOCK_SKEW_MILLIS
            // Samples recorded by the daemon can be older than the 45 min chart window (the app may
            // have been frozen for a while). Widen the interval window to cover the unattributed
            // backlog so those samples can still be assigned to the app that was on screen.
            val intervalWindowStart = maxOf(
                minOf(chartWindowStart, (pendingAttribution.firstOrNull()?.timestampMillis ?: fetchedAt) - 60_000L),
                fetchedAt - MAX_HISTORY_LOOKBACK_MILLIS,
            )
            var intervals: Map<String, List<TimeInterval>>? = null
            var history: List<SystemBatteryStatsSource.HistoryPoint> = emptyList()
            if (historyDue) {
                intervals = SystemBatteryStatsSource.parseForegroundIntervals(
                    statsText,
                    intervalWindowStart,
                    windowEnd,
                    fetchedAt,
                )
                // The chart's time axis counts recorded usage, so it is built only from our own
                // samples; seeding it with the system's 30 s history heartbeat would place points
                // on a different time base and make the curve longer than "已使用".
            }
            applyStats(powerDump, history, intervals, fetchedAt)
            if (historyDue) lastHistoryFetchMillis = fetchedAt
        }

        ensureVisualsForAggregates()
        rebuildScenariosIfChanged()
        publish()
    }

    /**
     * History is read at start-up and hourly. When a package has samples but no interval yet, an
     * extra (rate-limited) fetch happens so its row appears within a minute.
     */
    private fun isHistoryDue(nowMillis: Long): Boolean {
        if (lastHistoryFetchMillis == 0L) return true
        // The daemon attributes samples itself, so a history dump is only needed for the chart seed
        // and for the rare unattributed sample. Without the daemon the dump *is* the attribution
        // source, so keep it fresh (the foreground app can change at any time).
        val refreshInterval =
            if (daemonActive) HISTORY_REFRESH_INTERVAL_MILLIS else HISTORY_REFRESH_ACTIVE_MILLIS
        if (nowMillis - lastHistoryFetchMillis >= refreshInterval) return true
        return unattributedSampleSeen && nowMillis - lastHistoryFetchMillis >= MIN_HISTORY_RETRY_MILLIS
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
            rebuildIntervalIndex()
            retryPendingAttribution()
            backfillChartPackages()
        }
        // chart samples are recorded samples only (see the note in refreshStats).
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
        sessionUsedMillis = 0L
        sessionWeightedPowerMilliWattMillisSum = 0.0
        screenOnUsedMillis = 0L
        screenOnWeightedPowerMilliWattMillisSum = 0.0
        screenOffUsedMillis = 0L
        screenOffWeightedPowerMilliWattMillisSum = 0.0
        packageAggregates.clear()
        windowSamples.clear()
        chartSamples = emptyList()
        historyChartSamples = emptyList()
        historySeedAllowed = allowHistorySeed
        lastChartPointAtMillis = 0L
        lastSampleAtMillis = 0L
        lastSampleLevel = -1
        lastSampleWasScreenOn = false
        lastSampleWasDischarging = false
        lastSamplePowerMilliWatts = 0f
        lastSamplesPersistedAtMillis = 0L
        pendingAttribution.clear()
        unattributedSampleSeen = false
    }

    private fun archiveCurrentSession(nowMillis: Long, endLevelPercent: Int?) {
        val start = sessionStartMillis ?: return
        val trailingMillis = if (lastSampleWasDischarging && lastSampleAtMillis > 0L) {
            (nowMillis - lastSampleAtMillis).coerceIn(0L, SESSION_MAX_GAP_MILLIS)
        } else {
            0L
        }
        val durationMillis = sessionUsedMillis + trailingMillis
        if (durationMillis <= 0L) return
        val averagePowerWatts = PowerMath.averageDischargePowerWatts(
            sessionWeightedPowerMilliWattMillisSum + lastSamplePowerMilliWatts * trailingMillis,
            durationMillis,
        )
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
        sessionUsedMillis = stored.usedMillis
        sessionWeightedPowerMilliWattMillisSum = stored.weightedPowerMilliWattMillisSum
        screenOnUsedMillis = stored.screenOnUsedMillis
        screenOnWeightedPowerMilliWattMillisSum = stored.screenOnWeightedPowerMilliWattMillisSum
        screenOffUsedMillis = stored.screenOffUsedMillis
        screenOffWeightedPowerMilliWattMillisSum = stored.screenOffWeightedPowerMilliWattMillisSum
        packageAggregates.putAll(stored.packages)
        lastSampleAtMillis = stored.lastSampleAtMillis
        lastSampleLevel = stored.lastSampleLevel
        lastSamplePowerMilliWatts = stored.lastSamplePowerMilliWatts
        lastSampleWasScreenOn = stored.lastSampleWasScreenOn
        lastSampleWasDischarging = stored.lastSampleWasDischarging

        val threshold = System.currentTimeMillis() - PowerMath.CHART_RETENTION_MILLIS
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
                    usedMillis = sessionUsedMillis,
                    weightedPowerMilliWattMillisSum = sessionWeightedPowerMilliWattMillisSum,
                    screenOnUsedMillis = screenOnUsedMillis,
                    screenOnWeightedPowerMilliWattMillisSum = screenOnWeightedPowerMilliWattMillisSum,
                    screenOffUsedMillis = screenOffUsedMillis,
                    screenOffWeightedPowerMilliWattMillisSum = screenOffWeightedPowerMilliWattMillisSum,
                    lastSampleAtMillis = lastSampleAtMillis,
                    lastSampleLevel = lastSampleLevel,
                    lastSamplePowerMilliWatts = lastSamplePowerMilliWatts,
                    lastSampleWasScreenOn = lastSampleWasScreenOn,
                    lastSampleWasDischarging = lastSampleWasDischarging,
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
        val summary = currentSummary(screenOn = true)
        val screenOffSummary = currentSummary(screenOn = false)
        val mergedChartSamples = mergedChartSamples()
        _state.update {
            it.copy(
                snapshot = lastSnapshot,
                summary = summary,
                screenOffSummary = screenOffSummary,
                chartSamples = mergedChartSamples,
                chartWindowMillis = PowerMath.CHART_WINDOW_MILLIS,
                startLevelPercent = startLevelPercent ?: lastSnapshot?.levelPercent ?: it.startLevelPercent,
                sessionHistory = sessionHistory,
                scenarios = scenarios,
                fullCapacityWattHours = fullCapacityWattHours(),
                shizukuGranted = shizukuGranted,
                shizukuAvailable = shizukuAvailable,
                rootAvailable = rootAvailable,
                daemonState = daemonState,
                daemonRestarting = daemonRestartInFlight,
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

        /** `--history` (foreground intervals + chart seed) cadence while the daemon is running. */
        const val HISTORY_REFRESH_INTERVAL_MILLIS = 60L * 60_000L

        /** `--history` cadence without the daemon: it is then the only attribution source. */
        const val HISTORY_REFRESH_ACTIVE_MILLIS = 2L * 60_000L

        /** How far back a `--history` dump may be parsed to attribute daemon samples. */
        const val MAX_HISTORY_LOOKBACK_MILLIS = 12L * 60L * 60_000L

        /** Retry cadence for starting the root daemon after a failed attempt. */
        const val DAEMON_START_RETRY_MILLIS = 20_000L

        /** How often the real process table is checked while the app is alive. */
        const val DAEMON_PROBE_INTERVAL_MILLIS = 10_000L

        /** A cached state older than this is not trusted when the app returns to the foreground. */
        const val DAEMON_PROBE_FRESH_MILLIS = 30_000L

        /** A live process whose heartbeat has not moved for this long is wedged, not sampling. */
        const val DAEMON_HEARTBEAT_STALL_MILLIS = 120_000L

        /** Minimum gap between heartbeat-driven restarts. */
        const val DAEMON_RECOVERY_INTERVAL_MILLIS = 120_000L

        /** Charge-counter window used to calibrate daemon samples (1 mAh gauge resolution). */
        const val DAEMON_COUNTER_WINDOW_MILLIS = 60_000L

        /** Screen-on samples kept for attribution retry; older ones are dropped. */
        const val MAX_PENDING_ATTRIBUTIONS = 4_000

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

        /** Hard cap on retained chart points (45 min at 3 s + 12 h at 30 s ≈ 2 400). */
        const val MAX_CHART_SAMPLES = 3_000
    }
}
