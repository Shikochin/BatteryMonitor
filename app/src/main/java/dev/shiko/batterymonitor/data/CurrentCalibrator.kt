package dev.shiko.batterymonitor.data

import kotlin.math.abs
import kotlin.math.ln

/** Where the raw current of a tick came from (diagnostics / data-source reporting). */
internal enum class CurrentSource {
    /** `/sys/class/power_supply/battery/current_now` through root Shizuku. */
    SYSFS,

    /** `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`, public API. */
    BATTERY_MANAGER,

    /** The OPLUS `Battery current : N` line of `dumpsys battery`. */
    DUMPSYS,

    /** No sensor value; the charge-counter anchor implies the current. */
    COUNTER,
}

/**
 * Resolves the unknown unit of a raw current reading.
 *
 * Neither source documents its unit reliably on this ROM (sysfs `current_now` is 0.1 mA steps here,
 * `BatteryManager` and the OPLUS line look like milliamps), so every reading is tried under three
 * interpretations — µA, mA and 0.1 mA — and the chosen one must produce a power inside
 * [PowerMath.MIN_POWER_WATTS]…[PowerMath.MAX_POWER_WATTS].
 *
 * When the charge counter has produced a reference power, the interpretation closest to it wins.
 * Before that reference exists a plausible scale is **locked** and corrected as soon as the
 * counter catches up, so the UI still shows a live number every tick instead of waiting.
 */
internal object CurrentCalibrator {

    /** Divisors from raw value to milliamps: µA, mA, 0.1 mA. */
    private val DIVISORS = floatArrayOf(1000f, 1f, 10f)

    private const val MIN_RATIO = 0.33f
    private const val MAX_RATIO = 3f

    /** A locked scale is kept while the counter stays within this band (no flapping). */
    private const val LOCK_KEEP_MIN_RATIO = 0.5f
    private const val LOCK_KEEP_MAX_RATIO = 2f

    /** Power a typical idle-but-awake phone draws; used only to pick a scale before calibration. */
    private const val TYPICAL_POWER_WATTS = 1.2f

    data class Calibration(
        /** Calibrated current in mA, negative while discharging. */
        val milliAmps: Float,
        /** `|I| × V / 1000`, inside [PowerMath.MIN_POWER_WATTS]…[PowerMath.MAX_POWER_WATTS]. */
        val powerWatts: Float,
        /** Raw-to-mA divisor that won; lock it for the next reading. */
        val divisor: Float,
        /** `true` when the choice was backed by the charge-counter reference. */
        val calibrated: Boolean,
    )

    /**
     * @param rawCurrent raw sensor value (sign is re-applied from [discharging]).
     * @param referencePowerWatts charge-counter power, `null` or stale when unknown.
     * @param preferredDivisor divisor locked from a previous reading.
     */
    fun calibrate(
        rawCurrent: Long,
        voltageVolts: Float,
        referencePowerWatts: Float?,
        discharging: Boolean,
        preferredDivisor: Float? = null,
    ): Calibration? {
        if (voltageVolts <= 0f) return null
        val magnitude = abs(rawCurrent).toFloat()
        if (magnitude <= 0f) return null

        val candidates = ArrayList<Calibration>(DIVISORS.size)
        for (divisor in DIVISORS) {
            val milliAmps = magnitude / divisor
            val powerWatts = milliAmps * voltageVolts / 1000f
            if (powerWatts < PowerMath.MIN_POWER_WATTS || powerWatts > PowerMath.MAX_POWER_WATTS) continue
            candidates += Calibration(
                milliAmps = if (discharging) -milliAmps else milliAmps,
                powerWatts = powerWatts,
                divisor = divisor,
                calibrated = false,
            )
        }
        if (candidates.isEmpty()) return null

        // Once a scale has been calibrated, keep it unless the counter clearly disagrees. This
        // avoids flapping between the mA and 0.1 mA interpretations on a noisy sensor (which would
        // multiply a single app's average by ~10×).
        val locked = preferredDivisor?.let { divisor -> candidates.firstOrNull { it.divisor == divisor } }
        if (locked != null) {
            val ratio = referencePowerWatts?.takeIf { it > 0f }?.let { locked.powerWatts / it }
            if (ratio == null || ratio in LOCK_KEEP_MIN_RATIO..LOCK_KEEP_MAX_RATIO) {
                return locked.copy(calibrated = ratio != null)
            }
        }

        if (referencePowerWatts != null && referencePowerWatts > 0f) {
            val matching = candidates
                .filter {
                    val ratio = it.powerWatts / referencePowerWatts
                    ratio in MIN_RATIO..MAX_RATIO
                }
                .minByOrNull { abs(ln(it.powerWatts / referencePowerWatts)) }
            if (matching != null) return matching.copy(calibrated = true)
        }

        locked?.let { return it }
        return candidates.minByOrNull { abs(it.powerWatts - TYPICAL_POWER_WATTS) }
    }

    /** Current implied by a known counter power, used when the sensor gives no usable value. */
    fun currentFromPower(powerWatts: Float, voltageVolts: Float, discharging: Boolean): Float? {
        if (voltageVolts <= 0f || powerWatts <= 0f) return null
        val milliAmps = powerWatts * 1000f / voltageVolts
        return if (discharging) -milliAmps else milliAmps
    }
}
