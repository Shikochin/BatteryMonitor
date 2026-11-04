package dev.shiko.batterymonitor.data

import android.os.BatteryManager

/**
 * A battery reading whose current is **raw and unit-unknown**.
 *
 * Neither of the two Shizuku sources states its unit reliably on this ROM: sysfs `current_now`
 * returns `0.1 mA` steps here (`3594` = 359 mA) while the OPLUS line of `dumpsys battery` returns
 * milliamps (`658` = 658 mA). The raw value is therefore carried as-is and calibrated against the
 * charge counter by [CurrentCalibrator].
 */
internal data class RawBatteryReading(
    val levelPercent: Int,
    /** Pack voltage in volts (sysfs µV / dumpsys mV are both unambiguous). */
    val voltageVolts: Float,
    /** Raw current value, unit unknown; negative while discharging when the kernel says so. */
    val rawCurrent: Long?,
    val temperatureCelsius: Float,
    /** 2 = charging, 3 = discharging, 4 = not charging, 5 = full. */
    val status: Int,
    val chargeCounterMicroAmpHours: Long?,
    /** Full-pack charge from sysfs `bms/charge_full` / `battery/charge_full` (µAh → mAh). */
    val chargeFullMilliAmpHours: Float?,
) {
    val charging: Boolean
        get() = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

    val discharging: Boolean
        get() = status == BatteryManager.BATTERY_STATUS_DISCHARGING ||
            status == BatteryManager.BATTERY_STATUS_NOT_CHARGING

    val isUsable: Boolean
        get() = levelPercent in 0..100 && voltageVolts > 0f
}

/** A battery reading whose current has been calibrated to milliamps. */
internal data class BatteryReading(
    val levelPercent: Int,
    val voltageVolts: Float,
    /** Calibrated current in mA; negative while discharging. */
    val currentMilliAmps: Float,
    val temperatureCelsius: Float,
    val status: Int,
    val chargeCounterMicroAmpHours: Long?,
    val chargeFullMilliAmpHours: Float?,
) {
    val charging: Boolean
        get() = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

    val discharging: Boolean
        get() = status == BatteryManager.BATTERY_STATUS_DISCHARGING ||
            status == BatteryManager.BATTERY_STATUS_NOT_CHARGING
}

/**
 * Reads the battery through sysfs when Shizuku runs as **root** (Scene prefers these nodes because
 * they update continuously instead of in 1 mAh fuel-gauge steps).
 */
internal object SysfsBatterySource {

    private const val BASE = "/sys/class/power_supply"

    /**
     * One shell round-trip reads every node. `printf` keeps the output parseable; missing files are
     * skipped so a device without e.g. `bms/charge_full` still returns the rest.
     */
    val COMMAND: String = buildString {
        append("for p in")
        append(" battery/current_now battery/voltage_now battery/capacity battery/temp")
        append(" battery/status battery/charge_counter battery/charge_full")
        append(" bms/charge_full bms/charge_counter battery/cycle_count")
        append("; do")
        append(" v=\$(cat $BASE/\$p 2>/dev/null);")
        append(" [ -n \"\$v\" ] && echo \"\$p=\$v\";")
        append(" done")
    }

    /** Parses the `key=value` output of [COMMAND]; `null` when nothing usable was returned. */
    fun parse(text: String?): RawBatteryReading? {
        if (text.isNullOrBlank()) return null
        val values = HashMap<String, String>(12)
        for (line in text.lineSequence()) {
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            values[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
        }
        if (values.isEmpty()) return null

        val status = parseStatus(values["battery/status"])
        val voltageVolts = values["battery/voltage_now"]?.toLongOrNull()
            ?.let { it / 1_000_000f }
            ?.takeIf { it > 0f }
            ?: 0f
        val level = values["battery/capacity"]?.toIntOrNull() ?: -1
        val temperature = values["battery/temp"]?.toLongOrNull()?.let { it / 10f } ?: 0f
        val chargeCounter = values["battery/charge_counter"]?.toLongOrNull()
            ?: values["bms/charge_counter"]?.toLongOrNull()
        val chargeFullMicroAmpHours = values["bms/charge_full"]?.toLongOrNull()
            ?: values["battery/charge_full"]?.toLongOrNull()

        return RawBatteryReading(
            levelPercent = level,
            voltageVolts = voltageVolts,
            rawCurrent = values["battery/current_now"]?.toLongOrNull(),
            temperatureCelsius = temperature,
            status = status,
            chargeCounterMicroAmpHours = chargeCounter?.takeIf { it > 0L },
            chargeFullMilliAmpHours = chargeFullMicroAmpHours?.takeIf { it > 0L }?.let { it / 1000f },
        ).takeIf { it.isUsable }
    }

    /** sysfs reports `Charging` / `Discharging` / `Not charging` / `Full` (sometimes a number). */
    private fun parseStatus(raw: String?): Int {
        val value = raw?.trim().orEmpty()
        value.toIntOrNull()?.let { return it }
        return when {
            value.equals("Charging", ignoreCase = true) -> BatteryManager.BATTERY_STATUS_CHARGING
            value.equals("Discharging", ignoreCase = true) -> BatteryManager.BATTERY_STATUS_DISCHARGING
            value.equals("Not charging", ignoreCase = true) -> BatteryManager.BATTERY_STATUS_NOT_CHARGING
            value.equals("Full", ignoreCase = true) -> BatteryManager.BATTERY_STATUS_FULL
            else -> BatteryManager.BATTERY_STATUS_UNKNOWN
        }
    }
}
