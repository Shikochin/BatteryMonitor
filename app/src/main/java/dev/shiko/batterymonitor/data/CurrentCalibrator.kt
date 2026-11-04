package dev.shiko.batterymonitor.data

import kotlin.math.abs
import kotlin.math.ln

/**
 * Resolves the unknown unit of a raw current reading.
 *
 * Neither source documents its unit reliably on this ROM (sysfs `current_now` is 0.1 mA steps here,
 * the OPLUS `Battery current` line of `dumpsys battery` is milliamps), so every reading is compared
 * against the **charge counter**, which is unambiguous: `ΔµAh × V / Δt` is a real wattage.
 *
 * The raw value is tried under three interpretations — µA, mA and 0.1 mA — and the one whose power
 * lands closest to the counter reference (ratio in [0.33, 3]) wins. Without a reference (right
 * after start-up) nothing is returned, so the UI shows no current instead of a wrong one.
 */
internal object CurrentCalibrator {

    /** Divisors from raw value to milliamps: µA, mA, 0.1 mA. */
    private val DIVISORS = floatArrayOf(1000f, 1f, 10f)

    private const val MIN_RATIO = 0.33f
    private const val MAX_RATIO = 3f

    data class Calibration(
        /** Calibrated current in mA, negative while discharging. */
        val milliAmps: Float,
        /** `|I| × V / 1000`, guaranteed to be inside [PowerMath.MIN_POWER_WATTS]…[PowerMath.MAX_POWER_WATTS]. */
        val powerWatts: Float,
        /** Raw-to-mA divisor that won (useful for diagnostics). */
        val divisor: Float,
    )

    /**
     * @param rawCurrent raw sensor value (sign is re-applied from [discharging]).
     * @param voltageVolts pack voltage in volts.
     * @param referencePowerWatts charge-counter power over the last window, `null` when unknown.
     * @param discharging `true` when the device is on battery.
     */
    fun calibrate(
        rawCurrent: Long,
        voltageVolts: Float,
        referencePowerWatts: Float?,
        discharging: Boolean,
    ): Calibration? {
        if (voltageVolts <= 0f) return null
        if (referencePowerWatts == null || referencePowerWatts <= 0f) return null

        val magnitude = abs(rawCurrent).toFloat()
        if (magnitude <= 0f) return null

        var best: Calibration? = null
        var bestDistance = Float.MAX_VALUE
        for (divisor in DIVISORS) {
            val milliAmps = magnitude / divisor
            val powerWatts = milliAmps * voltageVolts / 1000f
            if (powerWatts < PowerMath.MIN_POWER_WATTS || powerWatts > PowerMath.MAX_POWER_WATTS) continue
            val ratio = powerWatts / referencePowerWatts
            if (ratio < MIN_RATIO || ratio > MAX_RATIO) continue
            val distance = abs(ln(ratio))
            if (distance < bestDistance) {
                bestDistance = distance
                best = Calibration(
                    milliAmps = if (discharging) -milliAmps else milliAmps,
                    powerWatts = powerWatts,
                    divisor = divisor,
                )
            }
        }
        return best
    }

    /** Current implied by a known counter power, used when the sensor gives no usable value. */
    fun currentFromPower(powerWatts: Float, voltageVolts: Float, discharging: Boolean): Float? {
        if (voltageVolts <= 0f || powerWatts <= 0f) return null
        val milliAmps = powerWatts * 1000f / voltageVolts
        return if (discharging) -milliAmps else milliAmps
    }
}
