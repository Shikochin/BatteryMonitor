package dev.shiko.batterymonitor.model

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A single reading of the device battery state.
 *
 * All values are already normalised to human units so that UI code never has to convert
 * micro-amps / milli-volts / tenths of a degree again.
 */
data class BatterySnapshot(
    /** Wall clock time (ms) at which the reading was taken. */
    val timestampMillis: Long,
    /** Battery level in percent, 0..100. */
    val levelPercent: Int,
    /** Voltage in volts, e.g. 4.402. */
    val voltageVolts: Float,
    /** Temperature in degrees Celsius, e.g. 28.5. */
    val temperatureCelsius: Float,
    /** Signed current in milli-amps: negative while discharging, positive while charging. */
    val currentMilliAmps: Float,
    /** Instantaneous power draw in watts (always positive). */
    val powerWatts: Float,
    /** Whether the device is currently plugged in / charging. */
    val charging: Boolean,
    /** Energy still stored in the battery, in watt-hours, when the device reports it. */
    val remainingEnergyWattHours: Float?,
    /** Whether the screen was interactive when the sample was taken. */
    val screenOn: Boolean,
)

/** How a section of the usage curve was recorded, mirroring Scene's chart legend. */
enum class ChartSegmentStyle {
    /** Solid blue: the segment starts while the screen is on and recording is current. */
    ScreenOn,

    /** Solid grey: the segment starts while the screen is off and recording is current. */
    ScreenOff,

    /** Dotted blue: screen on, but the recorder missed the next planned tick. */
    MissedRecordScreenOn,

    /** Dotted grey: screen off, but the recorder missed the next planned tick. */
    MissedRecordScreenOff,
}

/** One point of the "使用过程" (usage process) curve. */
data class ChartSample(
    val timestampMillis: Long,
    val levelPercent: Int,
    val style: ChartSegmentStyle,
    /** Foreground package at this instant; `null` for points seeded from the system history. */
    val packageName: String? = null,
    /** Signed power draw in mW (negative while discharging), used to place the app icons. */
    val powerMilliWatts: Float = 0f,
)

/** Aggregated numbers shown on the two summary rows. */
data class PowerSummary(
    /** Average power draw over the session, in watts. */
    val averagePowerWatts: Float,
    /** Time elapsed since monitoring started, in ms. */
    val usedMillis: Long,
    /** Estimated remaining runtime at the current average draw, in ms. */
    val estimatedRuntimeMillis: Long,
)

/** One entry of the "使用场景" (usage scenario) list. */
data class UsageScenario(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    /** Total time the app was in the foreground within the window. */
    val durationMillis: Long,
    /** Average power draw while the app was in the foreground, in watts. */
    val averagePowerWatts: Float,
    /** Average current while the app was in the foreground, in milliamps (Scene's mA mode). */
    val averageCurrentMilliAmps: Float = 0f,
    /** Average battery temperature while the app was in the foreground. */
    val averageTemperatureCelsius: Float,
    /** Hottest battery temperature observed while the app was in the foreground. */
    val maxTemperatureCelsius: Float,
)

/** A finished monitoring session, shown by the "history" action in the top bar. */
data class SessionRecord(
    val startedAtMillis: Long,
    val durationMillis: Long,
    val averagePowerWatts: Float,
    val energyWattHours: Float,
    val startLevelPercent: Int,
    val endLevelPercent: Int,
)

/**
 * Verified state of the detached root sampler.
 *
 * Deliberately tri-state: [UNKNOWN] is shown when the app cannot probe the process table (no
 * Shizuku grant, shell-only service, failed exec). The UI must never turn "I could not check"
 * into "running".
 */
enum class DaemonState { UNKNOWN, RUNNING, STOPPED }

/** Everything the screen needs, produced in one place by [dev.shiko.batterymonitor.data.PowerStatsRepository]. */
data class PowerUiState(
    val snapshot: BatterySnapshot? = null,
    val summary: PowerSummary = PowerSummary(0f, 0L, 0L),
    val screenOffSummary: PowerSummary = PowerSummary(0f, 0L, 0L),
    val chartSamples: List<ChartSample> = emptyList(),
    val scenarios: List<UsageScenario> = emptyList(),
    /** Width of the chart window in ms (e.g. 45 minutes). */
    val chartWindowMillis: Long = 45 * 60_000L,
    /** Battery level at the moment monitoring started, used for the "97%" style header. */
    val startLevelPercent: Int = 100,
    val loadingScenarios: Boolean = false,
    /** Finished sessions, newest first. */
    val sessionHistory: List<SessionRecord> = emptyList(),
    /**
     * Full-pack capacity in watt-hours: design capacity (adjusted for battery health — i.e. the
     * capacity the system learned for this battery) × nominal voltage. This is what the
     * "使用过程" card shows as `29.0Wh`; it is *not* the remaining charge.
     */
    val fullCapacityWattHours: Float? = null,
    /** True when the Shizuku service is running and this app has been granted access. */
    val shizukuGranted: Boolean = false,
    /** True when Shizuku is installed & running but permission has not been granted yet. */
    val shizukuAvailable: Boolean = false,
    /** True when the Shizuku shell actually runs as uid 0 (Sui/root), so sysfs is readable. */
    val rootAvailable: Boolean = false,
    /** Direct root provider label, e.g. `KernelSU(32716)` or `Magisk(29.0)`. */
    val rootProviderLabel: String? = null,
    /** Last verified state of the detached root sampler (collects while the app is frozen). */
    val daemonState: DaemonState = DaemonState.UNKNOWN,
    /** True while a manual "restart sampler" request is in flight. */
    val daemonRestarting: Boolean = false,
)
