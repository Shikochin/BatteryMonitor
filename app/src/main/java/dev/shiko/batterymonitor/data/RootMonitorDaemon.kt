package dev.shiko.batterymonitor.data

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * One raw sample written by the root sampling daemon.
 *
 * The daemon only records; every rule (screen-on + discharging, session gaps, per-package
 * attribution) is applied by [PowerStatsRepository] when the samples are ingested, so the daemon
 * can stay a dumb, dependency-free shell loop.
 */
internal data class DaemonSample(
    val timestampMillis: Long,
    val screenOn: Boolean,
    val levelPercent: Int,
    val voltageVolts: Float,
    /** Raw `/sys/class/power_supply/battery/current_now` value, unit unknown. */
    val rawCurrent: Long?,
    val temperatureCelsius: Float,
    /** 2 = charging, 3 = discharging, 4 = not charging, 5 = full. */
    val status: Int,
    val chargeCounterMicroAmpHours: Long?,
    val chargeFullMilliAmpHours: Float?,
    /** Package that was on screen for this sample (from `dumpsys activity`); `null` when unknown. */
    val foregroundPackage: String?,
)

/**
 * Manages the root sampling daemon: a detached `/system/bin/sh` loop started through Shizuku.
 *
 * Why a daemon: Android freezes a cached app process (`cgroup.freeze=1`, ~10 s after HOME on this
 * ROM), which stops the 3 s sampling loop inside the app. The daemon is a child of the Shizuku
 * server (uid 0), not of the app, so it keeps reading sysfs and appends samples to
 * `files/monitor/samples.log`; the app reads the new lines whenever it gets CPU again.
 */
internal class RootMonitorDaemon(context: Context) {

    private val appContext = context.applicationContext
    private val monitorDir = File(appContext.filesDir, MONITOR_DIR)
    private val logFile = File(monitorDir, LOG_FILE)
    private val pidFile = File(monitorDir, PID_FILE)

    /** A batch of not-yet-consumed samples plus the byte offset to continue from. */
    data class Batch(val samples: List<DaemonSample>, val nextOffset: Long)

    /**
     * Starts the daemon when it is not already alive. Requires root Shizuku (uid 0): the battery
     * sysfs nodes are not readable by the shell uid on this ROM.
     */
    fun ensureRunning(): Boolean {
        if (!ShizukuBridge.hasPermission()) {
            Log.d(TAG, "ensureRunning: no Shizuku permission")
            return false
        }
        if (ShizukuBridge.serverUid() != 0) {
            Log.d(TAG, "ensureRunning: Shizuku is not root (uid=${ShizukuBridge.serverUid()})")
            return false
        }
        if (isRunning()) {
            Log.d(TAG, "ensureRunning: already alive (pid=${currentPid()})")
            return true
        }

        val script = scriptBase64()
        if (script == null) {
            Log.w(TAG, "ensureRunning: cannot read the daemon asset")
            return false
        }
        val output = ShizukuBridge.exec(startCommand(script))
        val started = output?.contains(STARTED_MARKER) == true
        Log.d(TAG, "ensureRunning: started=$started output=${output?.trim()?.take(120)}")
        return started
    }

    /** Pid from the pidfile, or `null`. */
    fun currentPid(): String? =
        ShizukuBridge.exec("cat '${pidFile.absolutePath}' 2>/dev/null")?.trim()?.takeIf { it.isNotEmpty() }

    /** `true` when the pid in the pidfile is still a live process (checked through Shizuku). */
    fun isRunning(): Boolean {
        val output = ShizukuBridge.exec(
            "p=\$(cat '${pidFile.absolutePath}' 2>/dev/null); " +
                "if [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null; then echo " + ALIVE_MARKER + "; fi",
        ) ?: return false
        return output.contains(ALIVE_MARKER)
    }

    /**
     * Reads the samples written since [fromOffset].
     *
     * Direct file access is tried first (the file lives in the app's own data dir). If SELinux
     * refuses that — the file is created by root — the same bytes are read back through Shizuku.
     */
    fun readNew(fromOffset: Long): Batch {
        val batch = readDirect(fromOffset) ?: readThroughShizuku(fromOffset)
        if (batch.samples.isNotEmpty()) {
            Log.d(TAG, "readNew: ${batch.samples.size} samples, offset $fromOffset -> ${batch.nextOffset}")
        }
        return batch
    }

    /** Size of the log in bytes (used to skip the backlog after "clear data"). */
    fun currentSize(): Long {
        val direct = runCatching { if (logFile.exists()) logFile.length() else 0L }.getOrDefault(0L)
        if (direct > 0L) return direct
        return ShizukuBridge.exec("wc -c < '${logFile.absolutePath}' 2>/dev/null")
            ?.trim()?.toLongOrNull() ?: 0L
    }

    // ---------------------------------------------------------------------------------------------
    // Spawning
    // ---------------------------------------------------------------------------------------------

    private fun scriptBase64(): String? = runCatching {
        val bytes = appContext.assets.open(SCRIPT_ASSET).use { it.readBytes() }
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    /**
     * The script is shipped base64-encoded so root never has to read from the app's (SELinux
     * protected) data dir; it is decoded into `/data/local/tmp`, which root can execute from.
     */
    private fun startCommand(scriptBase64: String): String = buildString {
        append("mkdir -p '").append(monitorDir.absolutePath).append("' 2>/dev/null")
        append(" && printf '%s' '").append(scriptBase64).append("' | base64 -d > ").append(STAGED_SCRIPT)
        append(" && chmod 755 ").append(STAGED_SCRIPT)
        append(" && rm -f '").append(pidFile.absolutePath).append("'")
        append(" && (setsid sh ").append(STAGED_SCRIPT)
        append(" '").append(logFile.absolutePath).append("'")
        append(" '").append(pidFile.absolutePath).append("'")
        append(" >/dev/null 2>&1 < /dev/null &)")
        append(" ; sleep 1")
        append(" ; if [ -f '").append(pidFile.absolutePath).append("' ]; then echo ").append(STARTED_MARKER)
        append("; else echo ").append(FAILED_MARKER).append("; fi")
    }

    // ---------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------

    private fun readDirect(fromOffset: Long): Batch? = runCatching {
        if (!logFile.exists()) return@runCatching Batch(emptyList(), fromOffset)
        RandomAccessFile(logFile, "r").use { file ->
            val size = file.length()
            val start = if (fromOffset in 0..size) fromOffset else 0L
            if (size <= start) return@runCatching Batch(emptyList(), size)
            val length = minOf(size - start, MAX_BATCH_BYTES.toLong()).toInt()
            val buffer = ByteArray(length)
            file.seek(start)
            file.readFully(buffer)
            val lastNewline = buffer.lastIndexOf('\n'.code.toByte())
            if (lastNewline < 0) return@runCatching Batch(emptyList(), start)
            val complete = String(buffer, 0, lastNewline + 1, Charsets.UTF_8)
            Batch(parseSamples(complete), start + lastNewline + 1)
        }
    }.getOrNull()

    private fun readThroughShizuku(fromOffset: Long): Batch {
        val start = maxOf(fromOffset, 0L)
        val command = "f='${logFile.absolutePath}'; " +
            "s=\$(wc -c < \"\$f\" 2>/dev/null || echo 0); " +
            "echo \"@@\$s\"; " +
            "tail -c +$(( $start + 1 )) \"\$f\" 2>/dev/null"
        val output = ShizukuBridge.exec(command) ?: return Batch(emptyList(), start)
        val newline = output.indexOf('\n')
        if (newline < 0) return Batch(emptyList(), start)
        val size = output.substring(2, newline).trim().toLongOrNull() ?: start
        val samples = parseSamples(output.substring(newline + 1))
        val nextOffset = if (size >= start) size else size
        return Batch(samples, nextOffset)
    }

    private fun parseSamples(text: String): List<DaemonSample> {
        if (text.isEmpty()) return emptyList()
        val samples = ArrayList<DaemonSample>()
        for (line in text.lineSequence()) {
            val sample = parseLine(line) ?: continue
            samples += sample
        }
        return samples
    }

    private fun parseLine(line: String): DaemonSample? {
        val parts = line.split('|')
        if (parts.size < FIELD_COUNT) return null
        val seconds = parts[0].toLongOrNull() ?: return null
        if (seconds <= 0L) return null
        val level = parts[2].toIntOrNull() ?: return null
        if (level !in 0..100) return null
        val voltageMicroVolts = parts[3].toLongOrNull() ?: return null
        if (voltageMicroVolts <= 0L) return null
        return DaemonSample(
            timestampMillis = seconds * 1_000L,
            screenOn = parts[1] == "1",
            levelPercent = level,
            voltageVolts = voltageMicroVolts / 1_000_000f,
            rawCurrent = parts[4].toLongOrNull(),
            temperatureCelsius = parts[5].toLongOrNull()?.let { it / 10f } ?: 0f,
            status = parts[6].toIntOrNull() ?: 0,
            chargeCounterMicroAmpHours = parts[7].toLongOrNull()?.takeIf { it > 0L },
            chargeFullMilliAmpHours = parts[8].toLongOrNull()?.takeIf { it > 0L }?.let { it / 1000f },
            foregroundPackage = parts[9].trim().takeIf { it.isNotEmpty() },
        )
    }

    private companion object {
        const val TAG = "BatteryDaemon"
        const val MONITOR_DIR = "monitor"
        const val LOG_FILE = "samples.log"
        const val PID_FILE = "daemon.pid"
        const val SCRIPT_ASSET = "monitor-daemon.sh"
        const val STAGED_SCRIPT = "/data/local/tmp/bm-daemon.sh"
        const val STARTED_MARKER = "STARTED"
        const val FAILED_MARKER = "FAILED"
        const val ALIVE_MARKER = "ALIVE"
        const val FIELD_COUNT = 10

        /** Upper bound of bytes consumed in one ingest, so a big backlog never stalls the UI. */
        const val MAX_BATCH_BYTES = 512 * 1024
    }
}
