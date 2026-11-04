package dev.shiko.batterymonitor.data

import dev.shiko.batterymonitor.model.ChartSegmentStyle
import kotlin.math.abs

/**
 * One fully decoded battery sample.
 *
 * This is the "rich" counterpart of [dev.shiko.batterymonitor.model.ChartSample]: the chart only
 * needs the level and the segment style, while the summary / per-app attribution needs voltage,
 * temperature and the instantaneous power that was measured when the sample was taken.
 *
 * Instances are immutable; the repository owns the (mutable) window that holds them.
 */
internal data class PowerSample(
    val timestampMillis: Long,
    val levelPercent: Int,
    val voltageVolts: Float,
    val temperatureCelsius: Float,
    val currentMilliAmps: Float,
    val powerWatts: Float,
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

    fun contains(timestampMillis: Long): Boolean = timestampMillis >= startMillis && timestampMillis < endMillis

    /** Intersection with the chart window, or `null` when the interval falls completely outside. */
    fun clipTo(windowStartMillis: Long, windowEndMillis: Long): TimeInterval? {
        val clippedStart = maxOf(startMillis, windowStartMillis)
        val clippedEnd = minOf(endMillis, windowEndMillis)
        return if (clippedEnd > clippedStart) TimeInterval(clippedStart, clippedEnd) else null
    }
}

/** Aggregated per-app numbers derived from the battery samples that fall inside its foreground time. */
internal data class IntervalMetrics(
    val weightedPowerWatts: Float,
    val averageTemperatureCelsius: Float,
    val maxTemperatureCelsius: Float,
    val sampleCount: Int,
)

/**
 * Pure math for the power statistics screen: no Android APIs, no I/O, no coroutines.
 *
 * Keeping it free of framework types makes the (fiddly) unit handling reviewable in one place:
 *  - battery intent reports mV and tenths of °C,
 *  - [android.os.BatteryManager] reports µA / µAh,
 *  - the UI and the models expect V, °C, mA, W and Wh.
 */
internal object PowerMath {

    /**
     * Heartbeat of the live pipeline: the repository emits a new [dev.shiko.batterymonitor.model.PowerUiState]
     * every 3 s, and the chart's newest point is advanced on the same cadence.
     */
    const val TICK_INTERVAL_MILLIS = 3_000L

    /** "使用过程" chart horizon. */
    const val CHART_WINDOW_MILLIS = 45 * 60_000L

    /**
     * A gap larger than this means the process was frozen (the tick is 10 s, so real gaps are
     * 10–20 s). Dotted `MissedRecord*` segments are reserved for those freezes so solid lines keep
     * meaning "recorded on schedule".
     */
    const val MISSED_GAP_MILLIS = 45_000L

    /** Plausible bounds for the displayed / averaged draw. */
    const val MIN_POWER_WATTS = 0.05f
    const val MAX_POWER_WATTS = 30f

    /**
     * Per-app numbers come from the system's app-attributed energy only (screen/radio are accounted
     * separately in batterystats), so they are legitimately an order of magnitude below the device
     * average. Their floor is therefore 0.01 W instead of the device-wide 0.05 W.
     */
    const val MIN_APP_POWER_WATTS = 0.01f

    /** |current| above this is treated as a broken sensor reading (20 A would fry the phone). */
    const val MAX_PLAUSIBLE_CURRENT_MILLIAMPS = 20_000f

    /**
     * |current| below this (under one milliamp) cannot be a real phone discharge.
     * Values like that come from sensors that expose milliamps through the microamp property
     * (several OnePlus/OPPO kernels do); [BatterySampler] re-interprets them, and everything else
     * falls back to the fuel-gauge estimate.
     */
    const val MIN_PLAUSIBLE_CURRENT_MILLIAMPS = 1f

    /** 5 Ah design capacity, only used when the fuel gauge reports no charge counter. */
    const val FALLBACK_CAPACITY_MILLIAMP_HOURS = 5_000f

    /** Runtime estimates beyond three days are meaningless. */
    const val MAX_RUNTIME_MILLIS = 72L * 60L * 60L * 1000L

    /** Weight cap so that a long sleep does not let one stale sample dominate the session mean. */
    const val MAX_SAMPLE_WEIGHT_MILLIS = 5L * 60_000L

    /** Number of recent readings averaged for the "instantaneous" power shown in the UI. */
    const val SMOOTHING_WINDOW = 5

    const val MIN_PLAUSIBLE_VOLTAGE_VOLTS = 1.0f
    const val MAX_PLAUSIBLE_VOLTAGE_VOLTS = 10.0f

    /** Clamps a power reading into the range the screen is willing to display. */
    fun clampPower(watts: Float): Float = watts.coerceIn(MIN_POWER_WATTS, MAX_POWER_WATTS)

    /** Same clamp for per-app numbers, which are app-attributed energy only (floor 0.01 W). */
    fun clampAppPower(watts: Float): Float = watts.coerceIn(MIN_APP_POWER_WATTS, MAX_POWER_WATTS)

    /**
     * `true` when a raw current reading is worth using: finite, non-zero, at most ±20 A and at
     * least 1 mA in magnitude. A phone never draws less than a milliamp, so anything below that is
     * a sensor/unit bug rather than a measurement.
     */
    fun isPlausibleCurrent(milliamps: Float?): Boolean {
        if (milliamps == null || !milliamps.isFinite() || milliamps == 0f) return false
        val magnitude = abs(milliamps)
        return magnitude >= MIN_PLAUSIBLE_CURRENT_MILLIAMPS && magnitude <= MAX_PLAUSIBLE_CURRENT_MILLIAMPS
    }

    fun isPlausibleVoltage(volts: Float?): Boolean =
        volts != null && volts.isFinite() &&
            volts >= MIN_PLAUSIBLE_VOLTAGE_VOLTS && volts <= MAX_PLAUSIBLE_VOLTAGE_VOLTS

    /**
     * Power in watts from the instantaneous current: `|I(mA)| × V(V) / 1000`.
     *
     * Returns `null` when either input is missing or implausible so the caller can fall back to
     * the energy-delta estimate instead of publishing a garbage number.
     */
    fun powerFromCurrent(currentMilliamps: Float?, voltageVolts: Float?): Float? {
        if (!isPlausibleCurrent(currentMilliamps) || !isPlausibleVoltage(voltageVolts)) return null
        return clampPower(abs(currentMilliamps!!) * voltageVolts!! / 1000f)
    }

    /**
     * Remaining energy in Wh from the fuel gauge charge counter (µAh) and the pack voltage.
     * `null` when the device exposes no usable counter.
     */
    fun energyFromChargeCounter(microAmpHours: Long?, voltageVolts: Float?): Float? {
        if (microAmpHours == null || microAmpHours <= 0L || !isPlausibleVoltage(voltageVolts)) return null
        return microAmpHours / 1_000_000f * voltageVolts!!
    }

    /**
     * Coarse fallback: level% × capacity × voltage. Only used when the fuel gauge is silent;
     * [capacityMilliAmpHours] comes from `dumpsys batterystats` when Shizuku is available,
     * otherwise the 5 Ah design default is used.
     */
    fun energyFromLevelPercent(
        levelPercent: Int,
        voltageVolts: Float,
        capacityMilliAmpHours: Float = FALLBACK_CAPACITY_MILLIAMP_HOURS,
    ): Float {
        val level = levelPercent.coerceIn(0, 100)
        val capacity = if (capacityMilliAmpHours.isFinite() && capacityMilliAmpHours > 0f) {
            capacityMilliAmpHours
        } else {
            FALLBACK_CAPACITY_MILLIAMP_HOURS
        }
        return level / 100f * capacity / 1000f * voltageVolts
    }

    /**
     * Power in watts estimated from the change in stored energy between two points in time:
     * `|ΔWh| / Δh`.
     *
     * The caller owns the measurement window (it keeps the last "anchor" energy and timestamp), so
     * this only needs the elapsed time. The absolute delta also covers charging, where the stored
     * energy goes up and the charge power is what we want to display.
     */
    fun powerFromEnergyDelta(
        anchorWattHours: Float?,
        currentWattHours: Float?,
        elapsedMillis: Long,
    ): Float? {
        if (anchorWattHours == null || currentWattHours == null) return null
        if (elapsedMillis < 1_000L) return null
        val deltaWattHours = abs(anchorWattHours - currentWattHours)
        if (deltaWattHours <= 0f) return null
        return clampPower(deltaWattHours * 3_600_000f / elapsedMillis)
    }

    /**
     * `remainingWh / averagePower` expressed in ms, clamped to `0 … 72h`.
     * Returns 0 when there is no usable power or energy reading.
     */
    fun estimatedRuntimeMillis(remainingWattHours: Float?, averagePowerWatts: Float): Long {
        if (remainingWattHours == null || remainingWattHours <= 0f) return 0L
        if (averagePowerWatts <= 0f || !averagePowerWatts.isFinite()) return 0L
        val hours = remainingWattHours / averagePowerWatts
        if (!hours.isFinite() || hours <= 0f) return 0L
        return (hours * 3_600_000f).toLong().coerceIn(0L, MAX_RUNTIME_MILLIS)
    }

    /** Time-weighted mean power over the samples in the session; 0 when nothing was recorded. */
    fun timeWeightedMeanPower(samples: List<PowerSample>): Float {
        if (samples.isEmpty()) return 0f
        var weightedSum = 0.0
        var weightSum = 0.0
        for (index in samples.indices) {
            val sample = samples[index]
            val weight = sampleWeightMillis(samples, index)
            weightedSum += sample.powerWatts * weight
            weightSum += weight
        }
        if (weightSum <= 0.0) return 0f
        return (weightedSum / weightSum).toFloat()
    }

    /**
     * Power / temperature attribution for a set of foreground intervals.
     *
     * Every sample that falls inside one of [intervals] contributes with the time until the next
     * sample as its weight, which makes the result a time-weighted mean rather than a plain mean
     * over an unevenly sampled list. Returns `null` when no sample overlaps the intervals.
     */
    fun metricsForIntervals(
        samples: List<PowerSample>,
        intervals: List<TimeInterval>,
        fallbackPowerWatts: Float,
    ): IntervalMetrics? {
        if (samples.isEmpty() || intervals.isEmpty()) return null
        var weightedPower = 0.0
        var weightSum = 0.0
        var temperatureSum = 0.0
        var maxTemperature = Float.NEGATIVE_INFINITY
        var count = 0
        for (index in samples.indices) {
            val sample = samples[index]
            if (intervals.none { it.contains(sample.timestampMillis) }) continue
            val weight = sampleWeightMillis(samples, index)
            weightedPower += sample.powerWatts * weight
            weightSum += weight
            temperatureSum += sample.temperatureCelsius
            if (sample.temperatureCelsius > maxTemperature) maxTemperature = sample.temperatureCelsius
            count++
        }
        if (count == 0) return null
        val averagePower = if (weightSum > 0.0) (weightedPower / weightSum).toFloat() else fallbackPowerWatts
        return IntervalMetrics(
            weightedPowerWatts = clampPower(averagePower),
            averageTemperatureCelsius = (temperatureSum / count).toFloat(),
            maxTemperatureCelsius = maxTemperature,
            sampleCount = count,
        )
    }

    /** Chart segment style for a sample: missed-record variants win over plain on/off. */
    fun segmentStyle(screenOn: Boolean, missedRecord: Boolean): ChartSegmentStyle = when {
        missedRecord && screenOn -> ChartSegmentStyle.MissedRecordScreenOn
        missedRecord -> ChartSegmentStyle.MissedRecordScreenOff
        screenOn -> ChartSegmentStyle.ScreenOn
        else -> ChartSegmentStyle.ScreenOff
    }

    /** `true` when the process was asleep long enough to miss a planned tick. */
    fun isMissedRecord(gapMillis: Long): Boolean = gapMillis > MISSED_GAP_MILLIS

    /**
     * How much wall-clock time a sample represents: the distance to the next sample, capped so a
     * sleep gap cannot dominate, and never less than one second.
     */
    private fun sampleWeightMillis(samples: List<PowerSample>, index: Int): Double {
        val sample = samples[index]
        val nextTimestamp = if (index + 1 < samples.size) {
            samples[index + 1].timestampMillis
        } else {
            // The newest point is still "live"; one tick is the best available weight.
            sample.timestampMillis + TICK_INTERVAL_MILLIS
        }
        val gap = nextTimestamp - sample.timestampMillis
        return gap.coerceIn(1_000L, MAX_SAMPLE_WEIGHT_MILLIS).toDouble()
    }
}
