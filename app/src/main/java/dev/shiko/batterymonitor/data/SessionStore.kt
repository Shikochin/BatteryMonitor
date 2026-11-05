package dev.shiko.batterymonitor.data

import android.content.Context
import android.content.SharedPreferences
import dev.shiko.batterymonitor.model.ChartSegmentStyle
import dev.shiko.batterymonitor.model.SessionRecord
import kotlin.math.roundToInt

/** Persisted state of the running session (Scene keeps the equivalent in its SQLite `records`). */
internal data class StoredSession(
    val startMillis: Long,
    val startLevelPercent: Int?,
    val sampleCount: Int,
    /** Legacy unweighted sum, retained to migrate existing sessions. */
    val powerMilliWattSum: Double,
    val usedMillis: Long,
    /** Σ(power_mW × elapsed_ms), signed negative while discharging. */
    val weightedPowerMilliWattMillisSum: Double,
    val screenOnUsedMillis: Long,
    val screenOnWeightedPowerMilliWattMillisSum: Double,
    val screenOffUsedMillis: Long,
    val screenOffWeightedPowerMilliWattMillisSum: Double,
    val lastSampleAtMillis: Long,
    val lastSampleLevel: Int,
    val lastSamplePowerMilliWatts: Float,
    val lastSampleWasScreenOn: Boolean,
    val lastSampleWasDischarging: Boolean,
    val packages: Map<String, PackageAggregate>,
)

/**
 * Compact `SharedPreferences` persistence: chart samples, the running session (aggregates included)
 * and the finished-session history.
 *
 * No new dependency is allowed (no Room / kotlinx-serialization), so every structure is a flat
 * string: `…;…` for lists and `,` for fields.
 */
internal class SessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Bump the version when the encoding changes so stale blobs are dropped instead of parsed.
        if (prefs.getInt(KEY_VERSION, 0) != STORE_VERSION) {
            // The pause flag is a user preference, not session data: a store upgrade must never
            // silently turn recording back on, so it is carried across the wipe.
            val recordingPaused = prefs.getBoolean(KEY_RECORDING_PAUSED, false)
            prefs.edit()
                .clear()
                .putInt(KEY_VERSION, STORE_VERSION)
                .putBoolean(KEY_RECORDING_PAUSED, recordingPaused)
                .apply()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Chart samples
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
        prefs.edit().putString(KEY_SAMPLES, samples.joinToString(ENTRY_SEPARATOR, transform = ::encodeSample)).apply()
    }

    // ---------------------------------------------------------------------------------------------
    // Running session
    // ---------------------------------------------------------------------------------------------

    fun loadSession(): StoredSession? {
        val start = prefs.getLong(KEY_SESSION_START, NO_VALUE)
        if (start <= 0L) return null
        val sampleCount = prefs.getInt(KEY_SAMPLE_COUNT, 0)
        val powerSum = prefs.getLong(KEY_POWER_SUM, 0L).toDouble()
        val usedMillis = prefs.getLong(KEY_USED_MILLIS, NO_VALUE)
            .takeIf { it >= 0L } ?: sampleCount.toLong() * PowerMath.TICK_INTERVAL_MILLIS
        val weightedPowerSum = prefs.getLong(KEY_WEIGHTED_POWER_SUM, NO_VALUE)
            .takeIf { it != NO_VALUE }?.toDouble()
            ?: powerSum * PowerMath.TICK_INTERVAL_MILLIS
        val screenOnUsedMillis = prefs.getLong(KEY_SCREEN_ON_USED_MILLIS, NO_VALUE)
            .takeIf { it >= 0L } ?: (sampleCount.toLong() * PowerMath.TICK_INTERVAL_MILLIS)
        val screenOffUsedMillis = prefs.getLong(KEY_SCREEN_OFF_USED_MILLIS, NO_VALUE)
            .takeIf { it >= 0L } ?: (usedMillis - screenOnUsedMillis).coerceAtLeast(0L)
        val screenOnWeightedPowerSum = prefs.getLong(KEY_SCREEN_ON_WEIGHTED_POWER_SUM, NO_VALUE)
            .takeIf { it != NO_VALUE }?.toDouble() ?: powerSum * PowerMath.TICK_INTERVAL_MILLIS
        val screenOffWeightedPowerSum = prefs.getLong(KEY_SCREEN_OFF_WEIGHTED_POWER_SUM, NO_VALUE)
            .takeIf { it != NO_VALUE }?.toDouble() ?: weightedPowerSum - screenOnWeightedPowerSum
        return StoredSession(
            startMillis = start,
            startLevelPercent = prefs.getInt(KEY_START_LEVEL, NO_VALUE.toInt()).takeIf { it in 0..100 },
            sampleCount = sampleCount,
            powerMilliWattSum = powerSum,
            usedMillis = usedMillis,
            weightedPowerMilliWattMillisSum = weightedPowerSum,
            screenOnUsedMillis = screenOnUsedMillis,
            screenOnWeightedPowerMilliWattMillisSum = screenOnWeightedPowerSum,
            screenOffUsedMillis = screenOffUsedMillis,
            screenOffWeightedPowerMilliWattMillisSum = screenOffWeightedPowerSum,
            lastSampleAtMillis = prefs.getLong(KEY_LAST_SAMPLE_AT, 0L),
            lastSampleLevel = prefs.getInt(KEY_LAST_SAMPLE_LEVEL, -1),
            lastSamplePowerMilliWatts = prefs.getLong(KEY_LAST_SAMPLE_POWER, 0L).toFloat(),
            lastSampleWasScreenOn = prefs.getBoolean(KEY_LAST_SAMPLE_SCREEN_ON, false),
            lastSampleWasDischarging = prefs.getBoolean(KEY_LAST_SAMPLE_DISCHARGING, false),
            packages = AggregateCodec.decode(prefs.getString(KEY_PACKAGES, null)),
        )
    }

    fun saveSession(session: StoredSession?) {
        val editor = prefs.edit()
        if (session == null || session.startMillis <= 0L) {
            editor
                .remove(KEY_SESSION_START)
                .remove(KEY_START_LEVEL)
                .remove(KEY_SAMPLE_COUNT)
                .remove(KEY_POWER_SUM)
                .remove(KEY_USED_MILLIS)
                .remove(KEY_WEIGHTED_POWER_SUM)
                .remove(KEY_SCREEN_ON_USED_MILLIS)
                .remove(KEY_SCREEN_ON_WEIGHTED_POWER_SUM)
                .remove(KEY_SCREEN_OFF_USED_MILLIS)
                .remove(KEY_SCREEN_OFF_WEIGHTED_POWER_SUM)
                .remove(KEY_LAST_SAMPLE_AT)
                .remove(KEY_LAST_SAMPLE_LEVEL)
                .remove(KEY_LAST_SAMPLE_POWER)
                .remove(KEY_LAST_SAMPLE_SCREEN_ON)
                .remove(KEY_LAST_SAMPLE_DISCHARGING)
                .remove(KEY_PACKAGES)
                .apply()
            return
        }
        editor
            .putLong(KEY_SESSION_START, session.startMillis)
            .putInt(KEY_SAMPLE_COUNT, session.sampleCount)
            .putLong(KEY_POWER_SUM, session.powerMilliWattSum.toLong())
            .putLong(KEY_USED_MILLIS, session.usedMillis)
            .putLong(KEY_WEIGHTED_POWER_SUM, session.weightedPowerMilliWattMillisSum.toLong())
            .putLong(KEY_SCREEN_ON_USED_MILLIS, session.screenOnUsedMillis)
            .putLong(KEY_SCREEN_ON_WEIGHTED_POWER_SUM, session.screenOnWeightedPowerMilliWattMillisSum.toLong())
            .putLong(KEY_SCREEN_OFF_USED_MILLIS, session.screenOffUsedMillis)
            .putLong(KEY_SCREEN_OFF_WEIGHTED_POWER_SUM, session.screenOffWeightedPowerMilliWattMillisSum.toLong())
            .putLong(KEY_LAST_SAMPLE_AT, session.lastSampleAtMillis)
            .putInt(KEY_LAST_SAMPLE_LEVEL, session.lastSampleLevel)
            .putLong(KEY_LAST_SAMPLE_POWER, session.lastSamplePowerMilliWatts.toLong())
            .putBoolean(KEY_LAST_SAMPLE_SCREEN_ON, session.lastSampleWasScreenOn)
            .putBoolean(KEY_LAST_SAMPLE_DISCHARGING, session.lastSampleWasDischarging)
            .apply()
        session.startLevelPercent?.let { editor.putInt(KEY_START_LEVEL, it) }
        if (session.packages.isEmpty()) {
            editor.remove(KEY_PACKAGES)
        } else {
            editor.putString(KEY_PACKAGES, AggregateCodec.encode(session.packages))
        }
        editor.apply()
    }

    // ---------------------------------------------------------------------------------------------
    // Root sampling daemon (survives the app being frozen / killed)
    // ---------------------------------------------------------------------------------------------

    /** Byte offset into the daemon's sample log that has already been ingested. */
    fun loadDaemonOffset(): Long = prefs.getLong(KEY_DAEMON_OFFSET, 0L)

    fun saveDaemonOffset(offset: Long) {
        prefs.edit().putLong(KEY_DAEMON_OFFSET, offset).apply()
    }

    /** Timestamp of the last daemon sample folded into the session (dedupe across restarts). */
    fun loadDaemonLastSample(): Long = prefs.getLong(KEY_DAEMON_LAST_SAMPLE, 0L)

    fun saveDaemonLastSample(timestampMillis: Long) {
        prefs.edit().putLong(KEY_DAEMON_LAST_SAMPLE, timestampMillis).apply()
    }

    // ---------------------------------------------------------------------------------------------
    // Recording pause (a user preference, not session data: "clear data" keeps it)
    // ---------------------------------------------------------------------------------------------

    /**
     * `true` while the user has paused recording.
     *
     * Persisted because a pause must survive the app being killed and the phone rebooting: the
     * watchdog job reads this flag before it revives the sampler, and the repository restores it
     * on start-up instead of silently recording again.
     */
    fun loadRecordingPaused(): Boolean = prefs.getBoolean(KEY_RECORDING_PAUSED, false)

    fun saveRecordingPaused(paused: Boolean) {
        // `commit`, not `apply`: this flag is the only thing that stops the watchdog from reviving
        // the sampler, so it must be on disk before the user can kill the app. One small write on
        // a rare user action is worth the strictly synchronous guarantee.
        prefs.edit().putBoolean(KEY_RECORDING_PAUSED, paused).commit()
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
        prefs.edit().putString(KEY_HISTORY, records.joinToString(ENTRY_SEPARATOR, transform = ::encodeRecord)).apply()
    }

    /** Wipes the running session and the chart samples; the history list is kept. */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_SAMPLES)
            .remove(KEY_SESSION_START)
            .remove(KEY_START_LEVEL)
            .remove(KEY_SAMPLE_COUNT)
            .remove(KEY_POWER_SUM)
            .remove(KEY_USED_MILLIS)
            .remove(KEY_WEIGHTED_POWER_SUM)
            .remove(KEY_SCREEN_ON_USED_MILLIS)
            .remove(KEY_SCREEN_ON_WEIGHTED_POWER_SUM)
            .remove(KEY_SCREEN_OFF_USED_MILLIS)
            .remove(KEY_SCREEN_OFF_WEIGHTED_POWER_SUM)
            .remove(KEY_LAST_SAMPLE_AT)
            .remove(KEY_LAST_SAMPLE_LEVEL)
            .remove(KEY_LAST_SAMPLE_POWER)
            .remove(KEY_LAST_SAMPLE_SCREEN_ON)
            .remove(KEY_LAST_SAMPLE_DISCHARGING)
            .remove(KEY_PACKAGES)
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
        append(sample.powerMilliWatts.roundToInt())
        append(FIELD_SEPARATOR)
        append(sample.style.ordinal)
        append(FIELD_SEPARATOR)
        var flags = 0
        if (sample.screenOn) flags = flags or FLAG_SCREEN_ON
        if (sample.charging) flags = flags or FLAG_CHARGING
        append(flags)
        append(FIELD_SEPARATOR)
        append(sample.remainingEnergyWattHours?.let { (it * 10f).roundToInt() } ?: -1)
        append(FIELD_SEPARATOR)
        append(sample.packageName ?: "")
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
                powerMilliWatts = parts[5].toInt().toFloat(),
                style = STYLE_VALUES.getOrElse(parts[6].toInt()) { ChartSegmentStyle.ScreenOn },
                screenOn = flags and FLAG_SCREEN_ON != 0,
                charging = flags and FLAG_CHARGING != 0,
                remainingEnergyWattHours = parts[8].toInt().takeIf { it >= 0 }?.let { it / 10f },
                packageName = parts.getOrNull(9)?.takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
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
        const val STORE_VERSION = 3

        const val KEY_VERSION = "version"
        const val KEY_SAMPLES = "samples"
        const val KEY_SESSION_START = "session_start"
        const val KEY_START_LEVEL = "start_level"
        const val KEY_SAMPLE_COUNT = "sample_count"
        const val KEY_POWER_SUM = "power_sum"
        const val KEY_USED_MILLIS = "used_millis"
        const val KEY_WEIGHTED_POWER_SUM = "weighted_power_sum"
        const val KEY_SCREEN_ON_USED_MILLIS = "screen_on_used_millis"
        const val KEY_SCREEN_ON_WEIGHTED_POWER_SUM = "screen_on_weighted_power_sum"
        const val KEY_SCREEN_OFF_USED_MILLIS = "screen_off_used_millis"
        const val KEY_SCREEN_OFF_WEIGHTED_POWER_SUM = "screen_off_weighted_power_sum"
        const val KEY_LAST_SAMPLE_AT = "last_sample_at"
        const val KEY_LAST_SAMPLE_LEVEL = "last_sample_level"
        const val KEY_LAST_SAMPLE_POWER = "last_sample_power"
        const val KEY_LAST_SAMPLE_SCREEN_ON = "last_sample_screen_on"
        const val KEY_LAST_SAMPLE_DISCHARGING = "last_sample_discharging"
        const val KEY_PACKAGES = "packages"
        const val KEY_HISTORY = "history"
        const val KEY_DAEMON_OFFSET = "daemon_offset"
        const val KEY_DAEMON_LAST_SAMPLE = "daemon_last_sample"

        /** Not part of [STORE_VERSION]: older builds simply ignore the key. */
        const val KEY_RECORDING_PAUSED = "recording_paused"

        const val ENTRY_SEPARATOR = ";"
        const val FIELD_SEPARATOR = ","
        const val SAMPLE_FIELD_COUNT = 9
        const val RECORD_FIELD_COUNT = 6
        const val NO_VALUE = -1L

        const val FLAG_SCREEN_ON = 1
        const val FLAG_CHARGING = 2

        val STYLE_VALUES = ChartSegmentStyle.entries
    }
}
