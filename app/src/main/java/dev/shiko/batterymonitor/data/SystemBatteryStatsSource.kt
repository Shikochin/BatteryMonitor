package dev.shiko.batterymonitor.data

import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Parsers for the two `dumpsys` outputs that give us the system's *own* accounting:
 *
 *  - `dumpsys battery` → live pack state (level, mV, tenths of °C, fuel-gauge charge counter).
 *  - `dumpsys batterystats --charged` → per-UID estimated power in mAh for the current charge
 *    cycle, plus the design capacity the system learned for this battery.
 *
 * Both formats were verified against Android 17 / ColorOS output; the parser is intentionally
 * tolerant (it looks for the same keys anywhere in the text and never throws) so it also works on
 * AOSP and older OEM builds.
 *
 * This object has no Android dependencies, which keeps it unit-testable on a plain JVM.
 */
internal object SystemBatteryStatsSource {

    /** Battery service status codes, as printed by `dumpsys battery`. */
    const val STATUS_CHARGING = 2
    const val STATUS_DISCHARGING = 3
    const val STATUS_NOT_CHARGING = 4
    const val STATUS_FULL = 5

    /** Live state parsed from `dumpsys battery`. */
    data class BatteryDump(
        val levelPercent: Int?,
        val voltageVolts: Float?,
        val temperatureCelsius: Float?,
        val chargeCounterMicroAmpHours: Long?,
        /**
         * Current in mA when the OEM section prints one (`Battery current : 146` on ColorOS).
         * Magnitude only; used for display, power is derived from the charge counter.
         */
        val currentMilliAmps: Float?,
        val status: Int?,
        val plugged: Boolean,
    ) {
        val charging: Boolean
            get() = plugged || status == STATUS_CHARGING || status == STATUS_FULL

        val isUsable: Boolean
            get() = levelPercent != null || chargeCounterMicroAmpHours != null || voltageVolts != null
    }

    /** One `UID <id>: <mAh> ...` line of the estimated power section. */
    data class UidPower(
        val uid: Int,
        val milliAmpHours: Float,
        val foregroundMillis: Long?,
        val backgroundMillis: Long?,
        val foregroundServiceMillis: Long?,
        val cachedMillis: Long?,
    ) {
        /**
         * Foreground time (`fg` + `fgs`) used as the divisor when turning mAh into an average
         * wattage. On some ROMs `fg` is missing entirely while `fgs` carries the whole time.
         */
        val foregroundDurationMillis: Long
            get() = (foregroundMillis ?: 0L).coerceAtLeast(0L) +
                (foregroundServiceMillis ?: 0L).coerceAtLeast(0L)

        /** Any recorded time, used only when the app never had a foreground slice. */
        val totalDurationMillis: Long
            get() = foregroundDurationMillis +
                (backgroundMillis ?: 0L).coerceAtLeast(0L) +
                (cachedMillis ?: 0L).coerceAtLeast(0L)
    }

    /** Parsed `dumpsys batterystats --charged`. */
    data class PowerDump(
        /** Design capacity from `Estimated battery capacity:`. */
        val capacityMilliAmpHours: Int?,
        /**
         * Capacity the system *learned* for this battery (`Last learned battery capacity:`), which
         * already includes health degradation — this is what the "使用过程" card shows as `29.0Wh`.
         */
        val learnedCapacityMilliAmpHours: Int?,
        val computedDrainMilliAmpHours: Float?,
        val actualDrainMilliAmpHours: Float?,
        /** `Discharge: 136 mAh` — energy used since the last full charge. */
        val dischargeMilliAmpHours: Float?,
        /** `Time on battery: 33m 55s 81ms` — the window the discharge figure belongs to. */
        val timeOnBatteryMillis: Long?,
        val uidPower: List<UidPower>,
    ) {
        val isUsable: Boolean
            get() = capacityMilliAmpHours != null || uidPower.isNotEmpty()

        /**
         * Average draw over the current charge cycle: `mAh / 1000 × V / h`.
         *
         * This is the system's own number: it already includes screen, radio and CPU, unlike the
         * per-UID estimates which only cover the app-attributed slices.
         */
        fun averagePowerWatts(voltageVolts: Float): Float? {
            // After `dumpsys batterystats --reset` both values are near zero; refuse to divide the
            // noise and let the caller fall back to the sampling summary.
            val discharge = dischargeMilliAmpHours?.takeIf { it >= MIN_DISCHARGE_FOR_AVERAGE } ?: return null
            val millis = timeOnBatteryMillis?.takeIf { it >= MIN_TIME_ON_BATTERY_FOR_AVERAGE } ?: return null
            val hours = millis / 3_600_000f
            if (hours <= 0f || voltageVolts <= 0f) return null
            return discharge / 1000f * voltageVolts / hours
        }
    }

    /**
     * One point reconstructed from the `--history` stream of `dumpsys batterystats`.
     *
     * The history section is the system's own event log: `MM-DD HH:mm:ss.SSS <level> +screen/-screen
     * temp= volt= charge= ...`. Only [levelPercent] and [screenOn] are used for the chart; nothing
     * is ever interpolated, so a level that the system never recorded never appears on screen.
     */
    data class HistoryPoint(
        val timestampMillis: Long,
        val levelPercent: Int,
        val screenOn: Boolean,
    )

    /**
     * Parses the `Battery History` section into at most one point per [HEARTBEAT_MILLIS], plus one
     * point for every real state change (level change or screen on/off).
     *
     * @param windowStartMillis only points at or after this timestamp are returned.
     * @param windowEndMillis points after this timestamp (clock skew) are dropped.
     * @param nowMillis "now" used to attach a year to the `MM-DD` stamps and to detect year wrap.
     */
    fun parseBatteryHistory(
        text: String?,
        windowStartMillis: Long,
        windowEndMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<HistoryPoint> {
        if (text.isNullOrBlank()) return emptyList()

        val points = ArrayList<HistoryPoint>()
        var screenOn = true
        var screenKnown = false
        var lastLevel = Int.MIN_VALUE
        var lastScreen = true
        var lastEmittedAt = 0L

        for (rawLine in text.lineSequence()) {
            val match = HISTORY_RECORD.matchEntire(rawLine.trim()) ?: continue
            val values = match.groupValues
            val timestamp = historyTimestamp(values, nowMillis) ?: continue
            if (timestamp > windowEndMillis) continue

            val level = values[7].toIntOrNull()?.coerceIn(0, 100) ?: continue
            val rest = values[8]
            val turnsOn = SCREEN_ON_TOKEN.containsMatchIn(rest)
            val turnsOff = SCREEN_OFF_TOKEN.containsMatchIn(rest)
            if (turnsOn) {
                screenOn = true
                screenKnown = true
            } else if (turnsOff) {
                screenOn = false
                screenKnown = true
            }

            if (timestamp < windowStartMillis) {
                // Keep the running state warm so the first in-window point is already correct.
                lastLevel = level
                lastScreen = screenOn
                continue
            }

            val firstPoint = points.isEmpty()
            val levelChanged = level != lastLevel
            val screenChanged = screenKnown && screenOn != lastScreen
            val heartbeat = timestamp - lastEmittedAt >= HEARTBEAT_MILLIS
            if (!firstPoint && !levelChanged && !screenChanged && !heartbeat) continue

            points += HistoryPoint(timestampMillis = timestamp, levelPercent = level, screenOn = screenOn)
            lastEmittedAt = timestamp
            lastLevel = level
            lastScreen = screenOn
        }
        return points
    }

    /**
     * Attaches a year to an `MM-DD HH:mm:ss.SSS` history stamp.
     *
     * batterystats omits the year; the current year is assumed, and a stamp that would land in the
     * future (December data read in January) is moved back one year.
     */
    private fun historyTimestamp(values: List<String>, nowMillis: Long): Long? {
        val month = values[1].toIntOrNull() ?: return null
        val day = values[2].toIntOrNull() ?: return null
        val hour = values[3].toIntOrNull() ?: return null
        val minute = values[4].toIntOrNull() ?: return null
        val second = values[5].toIntOrNull() ?: return null
        val millis = values[6].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowMillis
        val year = calendar.get(Calendar.YEAR)
        calendar.set(year, month - 1, day, hour, minute, second)
        calendar.set(Calendar.MILLISECOND, millis)
        var timestamp = calendar.timeInMillis
        if (timestamp > nowMillis + HISTORY_FUTURE_SLACK_MILLIS) {
            calendar.set(Calendar.YEAR, year - 1)
            timestamp = calendar.timeInMillis
        }
        return timestamp
    }

    // ---------------------------------------------------------------------------------------------
    // dumpsys battery
    // ---------------------------------------------------------------------------------------------

    fun parseBatteryDump(text: String?): BatteryDump? {
        if (text.isNullOrBlank()) return null

        var rawLevel: Int? = null
        var scale: Int? = null
        var voltageMilliVolts: Int? = null
        var temperatureTenths: Int? = null
        var chargeCounter: Long? = null
        var oemCurrentMilliAmps: Float? = null
        var status: Int? = null
        var plugged = false

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith(KEY_LEVEL) ->
                    rawLevel = line.valueAfter(KEY_LEVEL).toIntOrNull()

                line.startsWith(KEY_SCALE) ->
                    scale = line.valueAfter(KEY_SCALE).toIntOrNull()

                line.startsWith(KEY_VOLTAGE) ->
                    voltageMilliVolts = line.valueAfter(KEY_VOLTAGE).toIntOrNull()

                line.startsWith(KEY_TEMPERATURE) ->
                    temperatureTenths = line.valueAfter(KEY_TEMPERATURE).toIntOrNull()

                line.startsWith(KEY_CHARGE_COUNTER) ->
                    chargeCounter = line.valueAfter(KEY_CHARGE_COUNTER).toLongOrNull()

                line.startsWith(KEY_STATUS) ->
                    status = line.valueAfter(KEY_STATUS).toIntOrNull()

                // ColorOS/OPPO add a vendor section at the top: "Battery current : 146".
                line.startsWith(KEY_OEM_CURRENT) -> {
                    val milliamps = line.valueAfter(KEY_OEM_CURRENT).toFloatOrNull()
                    if (milliamps != null && milliamps > 0f && milliamps <= MAX_OEM_CURRENT_MILLIAMPS) {
                        oemCurrentMilliAmps = milliamps
                    }
                }

                line.startsWith(KEY_AC_POWERED) ||
                    line.startsWith(KEY_USB_POWERED) ||
                    line.startsWith(KEY_WIRELESS_POWERED) ||
                    line.startsWith(KEY_DOCK_POWERED) -> {
                    val key = line.substringBefore(':')
                    if (line.valueAfter("$key:").equals("true", ignoreCase = true)) plugged = true
                }
            }
        }

        val levelPercent = when {
            rawLevel == null -> null
            scale != null && scale > 0 && scale != 100 ->
                (rawLevel * 100f / scale).roundToInt().coerceIn(0, 100)

            else -> rawLevel.coerceIn(0, 100)
        }
        val voltageVolts = voltageMilliVolts
            ?.takeIf { it in MIN_VOLTAGE_MILLIVOLTS..MAX_VOLTAGE_MILLIVOLTS }
            ?.let { it / 1000f }
        val temperatureCelsius = temperatureTenths
            ?.takeIf { it in MIN_TEMPERATURE_TENTHS..MAX_TEMPERATURE_TENTHS }
            ?.let { it / 10f }
        val chargeCounterMicroAmpHours = chargeCounter
            ?.takeIf { it > 0L && it <= MAX_CHARGE_COUNTER_MICRO_AMP_HOURS }

        val dump = BatteryDump(
            levelPercent = levelPercent,
            voltageVolts = voltageVolts,
            temperatureCelsius = temperatureCelsius,
            chargeCounterMicroAmpHours = chargeCounterMicroAmpHours,
            currentMilliAmps = oemCurrentMilliAmps,
            status = status,
            plugged = plugged,
        )
        return dump.takeIf { it.isUsable }
    }

    // ---------------------------------------------------------------------------------------------
    // dumpsys batterystats --charged
    // ---------------------------------------------------------------------------------------------

    fun parsePowerDump(text: String?): PowerDump? {
        if (text.isNullOrBlank()) return null

        var capacity: Int? = null
        var learnedCapacity: Int? = null
        var computedDrain: Float? = null
        var actualDrain: Float? = null
        var discharge: Float? = null
        var timeOnBattery: Long? = null
        val uidPower = ArrayList<UidPower>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val capacityMatch = CAPACITY_LINE.find(line)
            if (capacityMatch != null) {
                val groups = capacityMatch.groupValues
                groups.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { capacity = it }
                groups.getOrNull(2)?.toFloatOrNull()?.let { computedDrain = it }
                groups.getOrNull(3)?.toFloatOrNull()?.let { actualDrain = it }
                continue
            }

            val learnedCapacityMatch = LEARNED_CAPACITY_LINE.find(line)
            if (learnedCapacityMatch != null) {
                learnedCapacityMatch.groupValues.getOrNull(1)?.toIntOrNull()
                    ?.takeIf { it > 0 }?.let { learnedCapacity = it }
                continue
            }

            val estimatedCapacityMatch = ESTIMATED_CAPACITY_LINE.find(line)
            if (estimatedCapacityMatch != null) {
                if (capacity == null) {
                    estimatedCapacityMatch.groupValues.getOrNull(1)?.toIntOrNull()
                        ?.takeIf { it > 0 }?.let { capacity = it }
                }
                continue
            }

            val dischargeMatch = DISCHARGE_LINE.find(line)
            if (dischargeMatch != null) {
                dischargeMatch.groupValues.getOrNull(1)?.toFloatOrNull()?.let { discharge = it }
                continue
            }

            val timeOnBatteryMatch = TIME_ON_BATTERY_LINE.find(line)
            if (timeOnBatteryMatch != null) {
                timeOnBattery = parseDuration(timeOnBatteryMatch.groupValues.getOrNull(1))
                continue
            }

            val uidMatch = UID_LINE.matchEntire(line) ?: continue
            val uidToken = uidMatch.groupValues.getOrNull(1) ?: continue
            val milliAmpHours = uidMatch.groupValues.getOrNull(2)?.toFloatOrNull() ?: continue
            val rest = uidMatch.groupValues.getOrNull(3).orEmpty()
            if (milliAmpHours <= 0f) continue
            val uid = parseUid(uidToken) ?: continue
            uidPower += UidPower(
                uid = uid,
                milliAmpHours = milliAmpHours,
                foregroundMillis = durationIn(rest, FG_FIELD),
                backgroundMillis = durationIn(rest, BG_FIELD),
                foregroundServiceMillis = durationIn(rest, FGS_FIELD),
                cachedMillis = durationIn(rest, CACHED_FIELD),
            )
        }

        val dump = PowerDump(
            capacityMilliAmpHours = capacity,
            learnedCapacityMilliAmpHours = learnedCapacity ?: capacity,
            computedDrainMilliAmpHours = computedDrain,
            actualDrainMilliAmpHours = actualDrain,
            dischargeMilliAmpHours = discharge,
            timeOnBatteryMillis = timeOnBattery,
            uidPower = uidPower.sortedByDescending { it.milliAmpHours },
        )
        return dump.takeIf { it.isUsable }
    }

    /**
     * Reconstructs per-package foreground intervals from the `Battery History` event stream.
     *
     * The history records explicit transitions (`+fg=u0a482:"com.tencent.mobileqq"` when the app
     * comes to the foreground, `-fg=…` when it leaves), which is exactly what the per-app power
     * attribution needs: the device draw while that app was on screen.
     */
    fun parseForegroundIntervals(
        text: String?,
        windowStartMillis: Long,
        windowEndMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Map<String, List<TimeInterval>> {
        if (text.isNullOrBlank()) return emptyMap()

        // `fg=` covers ordinary foreground apps, `top=` covers the launcher / activities that only
        // appear as the top activity (the home screen has no `+fg=` event on ColorOS). Both become
        // foreground intervals and are merged afterwards.
        val fgOpen = HashMap<String, Long>()
        val topOpen = HashMap<String, Long>()
        val raw = LinkedHashMap<String, MutableList<TimeInterval>>()

        fun close(open: HashMap<String, Long>, packageName: String, atMillis: Long) {
            val start = open.remove(packageName) ?: return
            if (atMillis > start) {
                raw.getOrPut(packageName) { mutableListOf() }.add(TimeInterval(start, atMillis))
            }
        }

        for (rawLine in text.lineSequence()) {
            val match = HISTORY_RECORD.matchEntire(rawLine.trim()) ?: continue
            val values = match.groupValues
            val timestamp = historyTimestamp(values, nowMillis) ?: continue
            val rest = values[8]
            if (!rest.contains("fg=") && !rest.contains("top=")) continue

            for (token in rest.split(' ')) {
                val open: Boolean
                val map: HashMap<String, Long>
                when {
                    token.startsWith("+fg=") -> { open = true; map = fgOpen }
                    token.startsWith("-fg=") -> { open = false; map = fgOpen }
                    token.startsWith("+top=") -> { open = true; map = topOpen }
                    token.startsWith("-top=") -> { open = false; map = topOpen }
                    else -> continue
                }
                val packageName = token.substringAfter(':', "").trim('"')
                if (packageName.isEmpty()) continue
                if (open) {
                    close(map, packageName, timestamp)
                    map[packageName] = timestamp
                } else {
                    close(map, packageName, timestamp)
                }
            }
        }
        for ((packageName, start) in fgOpen.toList()) close(fgOpen, packageName, maxOf(start, windowEndMillis))
        for ((packageName, start) in topOpen.toList()) close(topOpen, packageName, maxOf(start, windowEndMillis))

        return raw.mapValues { (_, intervals) -> mergeIntervals(intervals, windowStartMillis, windowEndMillis) }
            .filterValues { it.isNotEmpty() }
    }

    /** Clips, sorts and coalesces overlapping intervals (an app can be both `fg` and `top`). */
    private fun mergeIntervals(
        intervals: List<TimeInterval>,
        windowStartMillis: Long,
        windowEndMillis: Long,
    ): List<TimeInterval> {
        val clipped = intervals
            .mapNotNull { it.clipTo(windowStartMillis, windowEndMillis) }
            .filter { it.durationMillis > 0L }
            .sortedBy { it.startMillis }
        if (clipped.isEmpty()) return emptyList()
        val merged = ArrayList<TimeInterval>(clipped.size)
        for (interval in clipped) {
            val last = merged.lastOrNull()
            if (last != null && interval.startMillis <= last.endMillis) {
                merged[merged.lastIndex] = TimeInterval(last.startMillis, maxOf(last.endMillis, interval.endMillis))
            } else {
                merged += interval
            }
        }
        return merged
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * `u0a168` → `10168` (user 0, appId 168); plain `2000` stays `2000`.
     * The formula is Android's own: `userId * 100000 + 10000 + appId`.
     */
    fun parseUid(token: String): Int? {
        if (token.isEmpty()) return null
        if (token[0] != 'u' && token[0] != 'U') return token.toIntOrNull()
        val aIndex = token.indexOfFirst { it == 'a' || it == 'A' }
        if (aIndex <= 1 || aIndex == token.lastIndex) return null
        val userId = token.substring(1, aIndex).toIntOrNull() ?: return null
        val appId = token.substring(aIndex + 1).toIntOrNull() ?: return null
        if (userId < 0 || appId < 0) return null
        return userId * 100_000 + 10_000 + appId
    }

    /**
     * Parses a `dumpsys batterystats` duration such as `20m 17s 944ms` or `1h 2m 3s 4ms`
     * into milliseconds. Returns `null` when the field carries no duration.
     */
    fun parseDuration(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        var total = 0L
        var found = false
        for (match in DURATION_TOKEN.findAll(text)) {
            val value = match.groupValues[1].toLongOrNull() ?: continue
            total += when (match.groupValues[2]) {
                "d" -> value * 86_400_000L
                "h" -> value * 3_600_000L
                "m" -> value * 60_000L
                "s" -> value * 1_000L
                "ms" -> value
                else -> 0L
            }
            found = true
        }
        return if (found) total else null
    }

    private fun durationIn(text: String, field: Regex): Long? {
        val match = field.find(text) ?: return null
        return parseDuration(match.groupValues.getOrNull(1))
    }

    private fun String.valueAfter(key: String): String =
        substringAfter(key, "").trim().trimStart(':').trim().removeSuffix("mAh").trim()

    private const val KEY_LEVEL = "level:"
    private const val KEY_SCALE = "scale:"
    private const val KEY_VOLTAGE = "voltage:"
    private const val KEY_TEMPERATURE = "temperature:"
    private const val KEY_CHARGE_COUNTER = "Charge counter:"
    private const val KEY_STATUS = "status:"
    private const val KEY_OEM_CURRENT = "Battery current"
    private const val KEY_AC_POWERED = "AC powered:"
    private const val KEY_USB_POWERED = "USB powered:"
    private const val KEY_WIRELESS_POWERED = "Wireless powered:"
    private const val KEY_DOCK_POWERED = "Dock powered:"

    private const val MIN_VOLTAGE_MILLIVOLTS = 1_000
    private const val MAX_VOLTAGE_MILLIVOLTS = 10_000
    private const val MIN_TEMPERATURE_TENTHS = -500
    private const val MAX_TEMPERATURE_TENTHS = 1_500
    private const val MAX_CHARGE_COUNTER_MICRO_AMP_HOURS = 100_000_000L
    private const val MAX_OEM_CURRENT_MILLIAMPS = 3_000f

    /** `Capacity: 7500, Computed drain: 3.74, actual drain: 3.74` */
    private val CAPACITY_LINE = Regex(
        """^Capacity:\s*(\d+),\s*Computed drain:\s*([0-9]+(?:\.[0-9]+)?),\s*actual drain:\s*([0-9]+(?:\.[0-9]+)?)""",
    )

    /** `Estimated battery capacity: 7500 mAh` */
    private val ESTIMATED_CAPACITY_LINE = Regex("""^Estimated battery capacity:\s*(\d+)\s*mAh""")

    /** `Last learned battery capacity: 7500 mAh` (health-adjusted, preferred). */
    private val LEARNED_CAPACITY_LINE = Regex("""^Last learned battery capacity:\s*(\d+)\s*mAh""")

    /** `Discharge: 136 mAh` */
    private val DISCHARGE_LINE = Regex("""^Discharge:\s*([0-9]+(?:\.[0-9]+)?)\s*mAh""")

    /** `Time on battery: 33m 55s 81ms (93.5%) realtime, 31m 39s 622ms (93.3%) uptime` */
    private val TIME_ON_BATTERY_LINE = Regex(
        """^Time on battery:\s*((?:[0-9]+\s*(?:ms|d|h|m|s)\s*)+)""",
    )

    /** `UID u0a168: 55.1 fg: 0 (20m 17s 944ms) bg: 0 (5s 471ms) fgs: 0 (12m 35s 994ms)` */
    private val UID_LINE = Regex("""^UID\s+(\S+):\s*([0-9]+(?:\.[0-9]+)?)\s*(.*)$""")

    private val FG_FIELD = Regex("""\bfg:\s*[0-9.]+\s*(?:\(([^)]*)\))?""")
    private val BG_FIELD = Regex("""\bbg:\s*[0-9.]+\s*(?:\(([^)]*)\))?""")
    private val FGS_FIELD = Regex("""\bfgs:\s*[0-9.]+\s*(?:\(([^)]*)\))?""")
    private val CACHED_FIELD = Regex("""\bcached:\s*[0-9.]+\s*(?:\(([^)]*)\))?""")

    /** `ms` must come before `m` so `944ms` is not read as 944 minutes. */
    private val DURATION_TOKEN = Regex("""(\d+)(ms|d|h|m|s)""")

    /**
     * A history record:
     * `  11-04 18:05:05.205 097 mobileRx=0 ... +running +screen temp=292 volt=4391 charge=6695`.
     * Groups: month, day, hour, minute, second, millis, level, rest.
     */
    private val HISTORY_RECORD = Regex(
        """^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+(\d{2,3})\b(.*)$""",
    )

    private val SCREEN_ON_TOKEN = Regex("""(?:^|\s)\+screen(?:\s|$)""")
    private val SCREEN_OFF_TOKEN = Regex("""(?:^|\s)-screen(?:\s|$)""")

    /** Below these, the charge-cycle averages are reset/noise and must not be shown. */
    private const val MIN_DISCHARGE_FOR_AVERAGE = 0.5f
    private const val MIN_TIME_ON_BATTERY_FOR_AVERAGE = 60_000L

    /** Chart heartbeat: one point every 30 s keeps solid segments between real state changes. */
    private const val HEARTBEAT_MILLIS = 30_000L

    /** Allow a few seconds of clock skew when deciding "this stamp is in the future". */
    private const val HISTORY_FUTURE_SLACK_MILLIS = 2L * 24L * 60L * 60L * 1000L
}

/**
 * Maps a `dumpsys battery` dump onto the raw reading: the OPLUS `Battery current` value is kept
 * as-is because its unit is not reliable; [CurrentCalibrator] resolves it against the fuel gauge.
 */
internal fun SystemBatteryStatsSource.BatteryDump.toRawReading(): RawBatteryReading = RawBatteryReading(
    levelPercent = levelPercent ?: 0,
    voltageVolts = voltageVolts ?: 0f,
    rawCurrent = currentMilliAmps?.toLong(),
    temperatureCelsius = temperatureCelsius ?: 0f,
    status = status ?: 0,
    chargeCounterMicroAmpHours = chargeCounterMicroAmpHours,
    chargeFullMilliAmpHours = null,
)
