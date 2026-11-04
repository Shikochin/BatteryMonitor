package dev.shiko.batterymonitor.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Formats a duration the way Scene does: `54s`, `3m36s`, `15m41s`, `12h13m`, `2h3m`.
 * Units larger than the two most significant ones are dropped.
 */
fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "0s"
    val totalSeconds = (millis + 500) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> if (minutes > 0) "${hours}h${minutes}m" else "${hours}h"
        minutes > 0 -> if (seconds > 0) "${minutes}m${seconds}s" else "${minutes}m"
        else -> "${seconds}s"
    }
}

/** `2.13W` style power label. */
fun formatWatts(watts: Float): String = String.format(Locale.US, "%.2fW", watts)

/** `610mA` style current label (Scene shows whole milliamps). */
fun formatMilliAmps(milliAmps: Float): String = "${milliAmps.roundToInt()}mA"

/** `28.5°C` style temperature label; [decimals] controls precision. */
fun formatCelsius(celsius: Float, decimals: Int = 1): String =
    if (decimals <= 0) {
        "${celsius.roundToInt()}°C"
    } else {
        String.format(Locale.US, "%.${decimals}f°C", celsius)
    }

/** `4.402v` style voltage label. */
fun formatVoltage(volts: Float): String = String.format(Locale.US, "%.3fv", volts)

/** `29.0Wh` style energy label. */
fun formatWattHours(wattHours: Float): String = String.format(Locale.US, "%.1fWh", wattHours)

/** Short axis label such as `5m`, `15m`, `45m` or `1h20m`. */
fun formatAxisMinutes(minutes: Int): String {
    if (minutes <= 0) return "0"
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h${minutes % 60}".trimEnd('m')
}

/** Rounds to one decimal place, used for the big stat numbers on the summary row. */
fun roundTo1(value: Float): Float = (value * 10f).roundToInt() / 10f

/** Whether two floats are visually equal in the UI (tolerates sensor jitter). */
fun nearlyEqual(a: Float, b: Float, tolerance: Float = 0.005f): Boolean = abs(a - b) <= tolerance
