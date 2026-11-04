package dev.shiko.batterymonitor.data

import android.content.Context
import android.content.SharedPreferences
import dev.shiko.batterymonitor.model.ChartSegmentStyle
import dev.shiko.batterymonitor.model.SessionRecord
import kotlin.math.roundToInt

/**
 * Compact [SharedPreferences] persistence for the monitoring session.
 *
 * No new dependency is allowed (no Room / kotlinx-serialization), so every list is encoded as one
 * flat string:
 *
 *  - samples  → `ts,level,mV,dC,mA,mW,style,flags,energyWh;…`
 *  - history  → `startedAt,duration,avgP_mW,energy_mWh,startLevel,endLevel;…`
 *
 * The whole chart window (≈180 points at the 15 s cadence) is a few kilobytes, far below the
 * `SharedPreferences` comfort zone.
 */
internal class SessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Bump the version when the encoding changes so stale blobs are dropped instead of parsed.
        if (prefs.getInt(KEY_VERSION, 0) != STORE_VERSION) {
            prefs.edit().clear().putInt(KEY_VERSION, STORE_VERSION).apply()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Samples
    // ---------------------------------------------------------------------------------------------

    fun loadSamples(): List<PowerSample> {
        val encoded = prefs.getString(KEY_SAMPLES, null)
        if (encoded.isNullOrEmpty()) return emptyList()
        return encoded.split(ENTRY_SEPARATOR).mapNotNull(::decodeSample)
    }

    fun saveSamples(samples: List<PowerSample>) {
        if (samples.isEmpty()) {
            prefs.edit().remove(KEY_SAMPLES).apply()
            return
        }
        val encoded = samples.joinToString(ENTRY_SEPARATOR) { encodeSample(it) }
        prefs.edit().putString(KEY_SAMPLES, encoded).apply()
    }

    // ---------------------------------------------------------------------------------------------
    // Session metadata
    // ---------------------------------------------------------------------------------------------

    fun loadSessionStartMillis(): Long? =
        prefs.getLong(KEY_SESSION_START, NO_VALUE).takeIf { it > 0L }

    fun saveSessionStartMillis(millis: Long?) {
        val editor = prefs.edit()
        if (millis == null || millis <= 0L) {
            editor.remove(KEY_SESSION_START)
        } else {
            editor.putLong(KEY_SESSION_START, millis)
        }
        editor.apply()
    }

    fun loadStartLevelPercent(): Int? =
        prefs.getInt(KEY_START_LEVEL, NO_VALUE.toInt()).takeIf { it in 0..100 }

    fun saveStartLevelPercent(levelPercent: Int?) {
        val editor = prefs.edit()
        if (levelPercent == null || levelPercent !in 0..100) {
            editor.remove(KEY_START_LEVEL)
        } else {
            editor.putInt(KEY_START_LEVEL, levelPercent)
        }
        editor.apply()
    }

    /** Session-wide time-weighted power accumulators (`Σ W·ms`, `Σ ms`) as `Long`s. */
    fun loadAccumulators(): Pair<Double, Double> = Pair(
        prefs.getLong(KEY_WEIGHTED_POWER, 0L).toDouble(),
        prefs.getLong(KEY_WEIGHT, 0L).toDouble(),
    )

    fun saveAccumulators(weightedPowerSum: Double, weightSum: Double) {
        prefs.edit()
            .putLong(KEY_WEIGHTED_POWER, weightedPowerSum.coerceAtLeast(0.0).toLong())
            .putLong(KEY_WEIGHT, weightSum.coerceAtLeast(0.0).toLong())
            .apply()
    }

    // ---------------------------------------------------------------------------------------------
    // Session history
    // ---------------------------------------------------------------------------------------------

    fun loadHistory(): List<SessionRecord> {
        val encoded = prefs.getString(KEY_HISTORY, null)
        if (encoded.isNullOrEmpty()) return emptyList()
        return encoded.split(ENTRY_SEPARATOR).mapNotNull(::decodeRecord)
    }

    fun saveHistory(records: List<SessionRecord>) {
        if (records.isEmpty()) {
            prefs.edit().remove(KEY_HISTORY).apply()
            return
        }
        val encoded = records.joinToString(ENTRY_SEPARATOR) { encodeRecord(it) }
        prefs.edit().putString(KEY_HISTORY, encoded).apply()
    }

    /** Wipes samples, session start/level and the accumulators; the history is kept. */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_SAMPLES)
            .remove(KEY_SESSION_START)
            .remove(KEY_START_LEVEL)
            .remove(KEY_WEIGHTED_POWER)
            .remove(KEY_WEIGHT)
            .apply()
    }

    // ---------------------------------------------------------------------------------------------
    // Encoding
    // ---------------------------------------------------------------------------------------------

    private fun encodeSample(sample: PowerSample): String = buildString {
        append(sample.timestampMillis)
        append(FIELD_SEPARATOR)
        append(sample.levelPercent)
        append(FIELD_SEPARATOR)
        append((sample.voltageVolts * 1000f).roundToInt())
        append(FIELD_SEPARATOR)
        append((sample.temperatureCelsius * 10f).roundToInt())
        append(FIELD_SEPARATOR)
        append(sample.currentMilliAmps.roundToInt())
        append(FIELD_SEPARATOR)
        append((sample.powerWatts * 1000f).roundToInt())
        append(FIELD_SEPARATOR)
        append(sample.style.ordinal)
        append(FIELD_SEPARATOR)
        append(sampleFlags(sample))
        append(FIELD_SEPARATOR)
        append(sample.remainingEnergyWattHours?.let { (it * 10f).roundToInt() } ?: -1)
    }

    private fun decodeSample(encoded: String): PowerSample? {
        val parts = encoded.split(FIELD_SEPARATOR)
        if (parts.size < SAMPLE_FIELD_COUNT) return null
        return runCatching {
            val flags = parts[7].toInt()
            PowerSample(
                timestampMillis = parts[0].toLong(),
                levelPercent = parts[1].toInt().coerceIn(0, 100),
                voltageVolts = parts[2].toInt() / 1000f,
                temperatureCelsius = parts[3].toInt() / 10f,
                currentMilliAmps = parts[4].toInt().toFloat(),
                powerWatts = parts[5].toInt() / 1000f,
                style = STYLE_VALUES.getOrElse(parts[6].toInt()) { ChartSegmentStyle.ScreenOn },
                screenOn = flags and FLAG_SCREEN_ON != 0,
                charging = flags and FLAG_CHARGING != 0,
                remainingEnergyWattHours = parts[8].toInt().takeIf { it >= 0 }?.let { it / 10f },
            )
        }.getOrNull()
    }

    private fun sampleFlags(sample: PowerSample): Int {
        var flags = 0
        if (sample.screenOn) flags = flags or FLAG_SCREEN_ON
        if (sample.charging) flags = flags or FLAG_CHARGING
        return flags
    }

    private fun encodeRecord(record: SessionRecord): String = buildString {
        append(record.startedAtMillis)
        append(FIELD_SEPARATOR)
        append(record.durationMillis)
        append(FIELD_SEPARATOR)
        append((record.averagePowerWatts * 1000f).roundToInt())
        append(FIELD_SEPARATOR)
        append((record.energyWattHours * 1000f).roundToInt())
        append(FIELD_SEPARATOR)
        append(record.startLevelPercent)
        append(FIELD_SEPARATOR)
        append(record.endLevelPercent)
    }

    private fun decodeRecord(encoded: String): SessionRecord? {
        val parts = encoded.split(FIELD_SEPARATOR)
        if (parts.size < RECORD_FIELD_COUNT) return null
        return runCatching {
            SessionRecord(
                startedAtMillis = parts[0].toLong(),
                durationMillis = parts[1].toLong(),
                averagePowerWatts = parts[2].toInt() / 1000f,
                energyWattHours = parts[3].toInt() / 1000f,
                startLevelPercent = parts[4].toInt(),
                endLevelPercent = parts[5].toInt(),
            )
        }.getOrNull()
    }

    private companion object {
        const val PREFS_NAME = "power_stats_session"
        const val STORE_VERSION = 2

        const val KEY_VERSION = "version"
        const val KEY_SAMPLES = "samples"
        const val KEY_SESSION_START = "session_start"
        const val KEY_START_LEVEL = "start_level"
        const val KEY_WEIGHTED_POWER = "weighted_power"
        const val KEY_WEIGHT = "weight"
        const val KEY_HISTORY = "history"

        const val ENTRY_SEPARATOR = ";"
        const val FIELD_SEPARATOR = ","
        const val SAMPLE_FIELD_COUNT = 9
        const val RECORD_FIELD_COUNT = 6
        const val NO_VALUE = -1L

        /** Bit flags packed into the sample's `flags` column. */
        const val FLAG_SCREEN_ON = 1
        const val FLAG_CHARGING = 2

        val STYLE_VALUES = ChartSegmentStyle.entries
    }
}
