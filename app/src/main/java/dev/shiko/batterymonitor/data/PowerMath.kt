package dev.shiko.batterymonitor.data

import dev.shiko.batterymonitor.model.ChartSegmentStyle

/**
 * One recorded battery sample (Scene's `records` row): the power statistics are all derived from a
 * list of these.
 *
 * Instances are immutable; the repository keeps a bounded window for the chart and running
 * aggregates for the session.
 */
internal data class PowerSample(
    val timestampMillis: Long,
    val levelPercent: Int,
    val voltageVolts: Float,
    val temperatureCelsius: Float,
    /** Signed current in mA: negative while discharging. */
    val currentMilliAmps: Float,
    /** `current × voltage` in mW (negative while discharging, like Scene's `power` column). */
    val powerMilliWatts: Float,
    val charging: Boolean,
    val remainingEnergyWattHours: Float?,
    val screenOn: Boolean,
    val style: ChartSegmentStyle,
)

/** Half-open wall-clock range `[startMillis, endMillis)` used for foreground attribution. */
internal data class TimeInterval(
    val startMillis: Long,
    val endMillis: Long,
) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0L)

    fun contains(timestampMillis: Long): Boolean =
        timestampMillis >= startMillis && timestampMillis < endMillis

    /** Intersection with the chart window, or `null` when the interval falls completely outside. */
    fun clipTo(windowStartMillis: Long, windowEndMillis: Long): TimeInterval? {
        val clippedStart = maxOf(startMillis, windowStartMillis)
        val clippedEnd = minOf(endMillis, windowEndMillis)
        return if (clippedEnd > clippedStart) TimeInterval(clippedStart, clippedEnd) else null
    }
}

/**
 * Constants and small helpers shared by the data layer.
 *
 * The maths follow Scene 9.4.18 (`Analyser-Power` + the SQL queries on its `records` table):
 *  - samples are only taken while **discharging and the screen is on**,
 *  - average power is `avg(current × voltage)`,
 *  - "已使用" is `sampleCount × 3 s`,
 *  - runtime is `remainingWh / avgPower × 0.9`,
 *  - capacity is `chargeFull_mAh × 3.86 V` (×2 for small dual-cell packs).
 */
internal object PowerMath {

    /** Sampling/tick cadence; Scene records every 1–3 s and treats a record as 3 s. */
    const val TICK_INTERVAL_MILLIS = 3_000L

    /** "使用过程" chart horizon. */
    const val CHART_WINDOW_MILLIS = 45 * 60_000L

    /** Scene's curve: a gap longer than this between two samples means "not recorded" (dotted). */
    const val MISSED_GAP_MILLIS = 10_000L

    /**
     * Seeded history points are 30 s apart by construction, so they use a wider gap threshold to
     * stay solid; only real holes in the system log become dotted.
     */
    const val HISTORY_MISSED_GAP_MILLIS = 45_000L

    /** Displayed instantaneous power is clamped so a broken reading cannot produce nonsense. */
    const val MIN_POWER_WATTS = 0.05f
    const val MAX_POWER_WATTS = 30f

    /** Li-ion nominal voltage for the pack capacity (`chargeFull_mAh × 3.86 V`). */
    const val NOMINAL_VOLTAGE_VOLTS = 3.86f

    /** Runtime shown to the user keeps Scene's 10 % degradation margin. */
    const val RUNTIME_DEGRADATION = 0.9f

    /** Packs at or below this are doubled: some phones report one cell of a two-cell pack. */
    const val DUAL_CELL_FULL_CAPACITY_THRESHOLD_MILLIAMP_HOURS = 2510f

    /** A current reading beyond this is a sensor glitch (Scene also restarts the session). */
    const val MAX_PLAUSIBLE_CURRENT_MILLIAMPS = 60_000f

    /** Clamps the displayed instantaneous power into a sane range. */
    fun clampPower(watts: Float): Float = watts.coerceIn(MIN_POWER_WATTS, MAX_POWER_WATTS)

    /** Chart segment style for a sample: missed-record variants win over plain on/off. */
    fun segmentStyle(screenOn: Boolean, missedRecord: Boolean): ChartSegmentStyle = when {
        missedRecord && screenOn -> ChartSegmentStyle.MissedRecordScreenOn
        missedRecord -> ChartSegmentStyle.MissedRecordScreenOff
        screenOn -> ChartSegmentStyle.ScreenOn
        else -> ChartSegmentStyle.ScreenOff
    }

    /** `true` when the gap between two samples is long enough to count as "not recorded". */
    fun isMissedRecord(gapMillis: Long, thresholdMillis: Long = MISSED_GAP_MILLIS): Boolean =
        gapMillis > thresholdMillis

    /** `chargeFull_mAh × 3.86 V`, doubled for packs that look like a single cell of a dual pack. */
    fun fullCapacityWattHours(
        chargeFullMilliAmpHours: Float?,
        levelPercent: Int,
    ): Float? {
        if (chargeFullMilliAmpHours == null || chargeFullMilliAmpHours <= 0f) return null
        val doubled = chargeFullMilliAmpHours <= DUAL_CELL_FULL_CAPACITY_THRESHOLD_MILLIAMP_HOURS &&
            levelPercent >= 5
        val milliAmpHours = if (doubled) chargeFullMilliAmpHours * 2f else chargeFullMilliAmpHours
        return milliAmpHours / 1000f * NOMINAL_VOLTAGE_VOLTS
    }

    /** Runtime from the remaining energy, with Scene's 0.9 degradation factor. */
    fun estimatedRuntimeMillis(remainingWattHours: Float?, averagePowerWatts: Float): Long {
        if (remainingWattHours == null || remainingWattHours <= 0f) return 0L
        if (averagePowerWatts <= 0f || !averagePowerWatts.isFinite()) return 0L
        val hours = remainingWattHours / averagePowerWatts * RUNTIME_DEGRADATION
        if (!hours.isFinite() || hours <= 0f) return 0L
        return (hours * 3_600_000f).toLong()
    }

    /** Remaining energy in Wh from the fuel-gauge charge counter and the pack voltage. */
    fun remainingWattHours(
        chargeCounterMicroAmpHours: Long?,
        voltageVolts: Float,
        levelPercent: Int,
        chargeFullMilliAmpHours: Float?,
    ): Float? {
        if (chargeCounterMicroAmpHours != null && chargeCounterMicroAmpHours > 0L) {
            return chargeCounterMicroAmpHours / 1_000_000f * voltageVolts
        }
        val full = fullCapacityWattHours(chargeFullMilliAmpHours, levelPercent) ?: return null
        return full * (levelPercent.coerceIn(0, 100) / 100f)
    }
}
