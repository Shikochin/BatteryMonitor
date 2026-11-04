package dev.shiko.batterymonitor.data

import android.content.Context
import android.util.Base64
import android.util.Log
import dev.shiko.batterymonitor.model.DaemonState
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
 * Result of one liveness probe against the real process table.
 *
 * [state] is deliberately conservative: `RUNNING` means *exactly one* sampler process was found,
 * it is the pid the pidfile names, and it has published a heartbeat. Anything else is `STOPPED`
 * (needs a restart) or `UNKNOWN` (the probe itself failed, so nothing can be claimed).
 */
internal data class DaemonStatus(
    val state: DaemonState,
    val pid: Int? = null,
    val instances: Int = 0,
    /** Epoch millis of the last heartbeat written by the daemon, when known. */
    val heartbeatMillis: Long? = null,
    /** Rated capacity published in the state file (mAh), when the daemon has read it. */
    val chargeFullMilliAmpHours: Float? = null,
    /** The running sampler was started from a different (older) copy of the script. */
    val outdated: Boolean = false,
)

/**
 * Manages the root sampling daemon: a detached `/system/bin/sh` loop started through Shizuku.
 *
 * Why a daemon: Android freezes a cached app process (`cgroup.freeze=1`, ~10 s after HOME on this
 * ROM), which stops the 3 s sampling loop inside the app. The daemon is a child of the Shizuku
 * server (uid 0), not of the app, so it keeps reading sysfs and appends samples to
 * `files/monitor/samples.log`; the app reads the new lines whenever it gets CPU again.
 *
 * Single instance is enforced twice: the script takes an atomic hard-link lock before sampling and
 * reaps every other sampler it finds, and [ensureRunning] additionally verifies the process table
 * before and after a start. [status] never trusts a cached flag — it asks `/proc` every time, so a
 * dead daemon can never be reported as running.
 */
internal class RootMonitorDaemon(context: Context) {

    private val appContext = context.applicationContext
    private val monitorDir = File(appContext.filesDir, MONITOR_DIR)
    private val logFile = File(monitorDir, LOG_FILE)
    private val pidFile = File(monitorDir, PID_FILE)
    private val stateFile = File(monitorDir, STATE_FILE)

    /** A batch of not-yet-consumed samples plus the byte offset to continue from. */
    data class Batch(val samples: List<DaemonSample>, val nextOffset: Long)

    /**
     * Reads the real state of the daemon from the process table.
     *
     * Blocking: one Shizuku exec. Call from a background dispatcher. `UNKNOWN` is returned when the
     * probe cannot be made at all (no permission, shell-only Shizuku, exec failure) — in that case
     * the caller must not claim either "running" or "stopped".
     */
    @Synchronized
    fun status(): DaemonStatus {
        if (!ShizukuBridge.hasPermission()) return DaemonStatus(DaemonState.UNKNOWN)
        // A shell-only service can never start (or read) this daemon, so "stopped" is accurate;
        // only a missing grant leaves the real state unknowable.
        if (ShizukuBridge.serverUid() != 0) return DaemonStatus(DaemonState.STOPPED)

        val probe = probe() ?: return DaemonStatus(DaemonState.UNKNOWN)
        val state = when {
            probe.pids.isEmpty() -> DaemonState.STOPPED
            // A live sampler that is not the one the pidfile names is either a leftover from an
            // older build or a second instance; both need to be replaced, not reported as healthy.
            probe.pids.size == 1 && probe.pids[0] == probe.pidfilePid && probe.heartbeatMillis != null ->
                DaemonState.RUNNING
            else -> DaemonState.STOPPED
        }
        return DaemonStatus(
            state = state,
            pid = probe.pids.singleOrNull(),
            instances = probe.pids.size,
            heartbeatMillis = probe.heartbeatMillis,
            chargeFullMilliAmpHours = probe.chargeFullMilliAmpHours,
            outdated = state == DaemonState.RUNNING && probe.scriptVersion != SCRIPT_VERSION,
        )
    }

    /**
     * Rated capacity from the daemon's state file (mAh), when it has one.
     *
     * The sample lines no longer repeat `charge_full` (it is constant for a session), so the
     * ingest reads it from the small state file instead. Direct access works because the file
     * lives in the app's own data dir; Shizuku is only a fallback for SELinux surprises.
     */
    fun chargeFullMilliAmpHours(): Float? {
        val text = runCatching { stateFile.readText() }.getOrNull()
            ?: ShizukuBridge.exec("cat '${stateFile.absolutePath}' 2>/dev/null")
            ?: return null
        return text.trim().split('|').getOrNull(4)
            ?.toLongOrNull()?.takeIf { it > 0L }?.let { it / 1000f }
    }

    /**
     * Starts the daemon when it is not already the single healthy instance.
     *
     * Requires root Shizuku (uid 0): the battery sysfs nodes are not readable by the shell uid on
     * this ROM. Returns `true` only when the process table was re-checked afterwards and shows
     * exactly one sampler with a fresh heartbeat.
     */
    @Synchronized
    fun ensureRunning(): Boolean {
        if (!ShizukuBridge.hasPermission()) {
            Log.d(TAG, "ensureRunning: no Shizuku permission")
            return false
        }
        if (ShizukuBridge.serverUid() != 0) {
            Log.d(TAG, "ensureRunning: Shizuku is not root (uid=${ShizukuBridge.serverUid()})")
            return false
        }

        val before = status()
        if (before.state == DaemonState.RUNNING && !before.outdated) {
            Log.d(TAG, "ensureRunning: already alive (pid=${before.pid})")
            return true
        }
        if (before.outdated) {
            Log.d(TAG, "ensureRunning: sampler runs an older script, replacing it")
        }
        // Leftovers (legacy build, duplicate, stale pidfile) are removed before the fresh start;
        // the script would reap them too, but stopping first keeps the log free of a second writer.
        if (before.instances > 0) {
            Log.d(TAG, "ensureRunning: replacing ${before.instances} existing sampler(s)")
            stopAll()
        }
        if (!start()) return false

        val after = status()
        Log.d(TAG, "ensureRunning: verified=${after.state} pid=${after.pid} instances=${after.instances}")
        return after.state == DaemonState.RUNNING
    }

    /**
     * Kills every running sampler and starts exactly one fresh daemon.
     *
     * Used by the manual "restart sampler" action and by the heartbeat watchdog. The stop scan is
     * self-contained (it does not rely on the staged script's version), so an upgrade from an old
     * build cannot leave a second sampler behind.
     */
    @Synchronized
    fun restart(): Boolean {
        Log.d(TAG, "restart: stopping all samplers")
        stopAll()
        if (!start()) return false
        val after = status()
        Log.d(TAG, "restart: verified=${after.state} pid=${after.pid} instances=${after.instances}")
        return after.state == DaemonState.RUNNING
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
    // Probing
    // ---------------------------------------------------------------------------------------------

    private data class Probe(
        val pids: List<Int>,
        val pidfilePid: Int?,
        val heartbeatMillis: Long?,
        val chargeFullMilliAmpHours: Float?,
        val scriptVersion: Int?,
    )

    /**
     * One shell round-trip: list every sampler process, then read the pidfile and the heartbeat.
     *
     * `ps -o ARGS` is used instead of a per-pid `/proc` sweep because forking `tr` once per process
     * takes seconds on a busy system. `sh -c` wrappers are skipped — their command text contains
     * this script's name, and the probe's own shell must never be counted (that was exactly how a
     * dead daemon used to be reported as alive).
     */
    private fun probe(): Probe? {
        val output = ShizukuBridge.exec(probeCommand(), timeoutMillis = PROBE_TIMEOUT_MILLIS) ?: return null
        if (!output.contains(END_MARKER)) return null

        val pids = ArrayList<Int>()
        var pidfilePid: Int? = null
        var heartbeatMillis: Long? = null
        var chargeFullMilliAmpHours: Float? = null
        var scriptVersion: Int? = null
        var processCount = 0
        for (line in output.lineSequence()) {
            when {
                line.startsWith(SAMPLER_PREFIX) ->
                    line.removePrefix(SAMPLER_PREFIX).trim().toIntOrNull()?.let { pids += it }

                line.startsWith(PIDFILE_PREFIX) ->
                    pidfilePid = line.removePrefix(PIDFILE_PREFIX).trim().toIntOrNull()

                line.startsWith(PROCESS_COUNT_PREFIX) ->
                    processCount = line.removePrefix(PROCESS_COUNT_PREFIX).trim().toIntOrNull() ?: 0

                line.startsWith(HEARTBEAT_PREFIX) -> {
                    val fields = line.removePrefix(HEARTBEAT_PREFIX).trim().split('|')
                    val pid = fields.getOrNull(0)?.toIntOrNull()
                    val seconds = fields.getOrNull(1)?.toLongOrNull()
                    if (pidfilePid == null) pidfilePid = pid
                    if (seconds != null && seconds > 0L) heartbeatMillis = seconds * 1_000L
                    chargeFullMilliAmpHours = fields.getOrNull(4)
                        ?.toLongOrNull()?.takeIf { it > 0L }?.let { it / 1000f }
                    scriptVersion = fields.getOrNull(5)?.toIntOrNull()
                }
            }
        }
        // `ps` prints a header plus at least one process on any working system. Fewer lines means
        // the probe itself failed (e.g. an unsupported `-o`), and an empty pid list must not be
        // mistaken for "the daemon is stopped".
        if (processCount < 2) return null
        return Probe(pids, pidfilePid, heartbeatMillis, chargeFullMilliAmpHours, scriptVersion)
    }

    private fun probeCommand(): String = buildString {
        append("ps -A -o PID,ARGS 2>/dev/null | while read -r p a; do ")
        append("case \"\$a\" in 'sh -c '*) ;; '/system/bin/sh -c '*) ;; ")
        append("*bm-daemon.sh*) echo \"P:\$p\" ;; esac; done; ")
        append("echo \"F:\$(cat '").append(pidFile.absolutePath).append("' 2>/dev/null)\"; ")
        append("echo \"H:\$(cat '").append(stateFile.absolutePath).append("' 2>/dev/null)\"; ")
        append("echo \"N:\$(ps -A -o PID,ARGS 2>/dev/null | wc -l)\"; ")
        append("echo ").append(END_MARKER)
    }

    // ---------------------------------------------------------------------------------------------
    // Spawning / stopping
    // ---------------------------------------------------------------------------------------------

    private fun stopAll() {
        ShizukuBridge.exec(stopCommand(), timeoutMillis = STOP_TIMEOUT_MILLIS)
    }

    /**
     * The stop scan is intentionally self-contained: it matches the sampler's cmdline directly
     * instead of calling the staged script, so it also works when the staged copy is from an older
     * build (an old copy would treat `--stop` as a log path and spawn yet another daemon).
     */
    private fun stopCommand(): String = buildString {
        append("ps -A -o PID,ARGS 2>/dev/null | while read -r p a; do ")
        append("case \"\$a\" in 'sh -c '*) ;; '/system/bin/sh -c '*) ;; ")
        append("*bm-daemon.sh*) kill -9 \"\$p\" 2>/dev/null ;; esac; done; ")
        append("rm -f '").append(pidFile.absolutePath).append("' ")
        append("'").append(stateFile.absolutePath).append("' ")
        append("'").append(LOCK_PATH).append("' 2>/dev/null; ")
        append("echo ").append(STOPPED_MARKER)
    }

    private fun start(): Boolean {
        val script = scriptBase64()
        if (script == null) {
            Log.w(TAG, "start: cannot read the daemon asset")
            return false
        }
        val output = ShizukuBridge.exec(startCommand(script), timeoutMillis = START_TIMEOUT_MILLIS)
            ?: return false
        val started = output.contains(STARTED_MARKER)
        Log.d(TAG, "start: started=$started output=${output.trim().take(160)}")
        return started
    }

    private fun scriptBase64(): String? = runCatching {
        val bytes = appContext.assets.open(SCRIPT_ASSET).use { it.readBytes() }
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    /**
     * The script is shipped base64-encoded so root never has to read from the app's (SELinux
     * protected) data dir; it is decoded into `/data/local/tmp`, which root can execute from.
     *
     * The staged copy is replaced atomically (`mv`): writing over a script that an old daemon is
     * still executing can corrupt its loop mid-iteration.
     */
    private fun startCommand(scriptBase64: String): String = buildString {
        append("mkdir -p '").append(monitorDir.absolutePath).append("' 2>/dev/null")
        append(" && printf '%s' '").append(scriptBase64).append("' | base64 -d > ")
        append(STAGED_SCRIPT).append(".new")
        append(" && chmod 755 ").append(STAGED_SCRIPT).append(".new")
        append(" && mv -f ").append(STAGED_SCRIPT).append(".new ").append(STAGED_SCRIPT)
        append(" && (setsid sh ").append(STAGED_SCRIPT).append(" --run")
        append(" '").append(logFile.absolutePath).append("'")
        append(" '").append(pidFile.absolutePath).append("'")
        append(" '").append(stateFile.absolutePath).append("'")
        append(" '").append(LOCK_PATH).append("'")
        append(" >/dev/null 2>&1 </dev/null &)")
        // The pidfile and the heartbeat are published only after every other sampler is gone, so
        // this poll doubles as a real "started and single" check.
        append(" ; i=0; while [ \$i -lt 40 ]; do ")
        append("p=\$(cat '").append(pidFile.absolutePath).append("' 2>/dev/null); ")
        append("if [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null && [ -s '")
        append(stateFile.absolutePath).append("' ]; then echo ").append(STARTED_MARKER)
        append("; exit 0; fi; sleep 0.1; i=\$((i+1)); done; echo ").append(FAILED_MARKER)
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
        // New lines carry 9 fields (no rated capacity); lines written before the change still
        // carry 10 (charge_full before the package). Both are read so an upgrade does not drop
        // the backlog that is already in the log.
        val legacy = parts.size >= LEGACY_FIELD_COUNT
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
            chargeFullMilliAmpHours = if (legacy) {
                parts[8].toLongOrNull()?.takeIf { it > 0L }?.let { it / 1000f }
            } else {
                null
            },
            foregroundPackage = (if (legacy) parts[9] else parts[8])
                .trim().takeIf { it.isNotEmpty() },
        )
    }

    private companion object {
        const val TAG = "BatteryDaemon"
        const val MONITOR_DIR = "monitor"
        const val LOG_FILE = "samples.log"
        const val PID_FILE = "daemon.pid"
        const val STATE_FILE = "daemon.state"
        const val SCRIPT_ASSET = "monitor-daemon.sh"
        const val STAGED_SCRIPT = "/data/local/tmp/bm-daemon.sh"

        /** Must match `VERSION` in `monitor-daemon.sh`; bump both when the script changes. */
        const val SCRIPT_VERSION = 3

        /** Global lock file: only one sampler may hold it, across app versions and processes. */
        const val LOCK_PATH = "/data/local/tmp/bm-daemon.lock"

        const val STARTED_MARKER = "STARTED"
        const val FAILED_MARKER = "FAILED"
        const val STOPPED_MARKER = "STOPPED"
        const val END_MARKER = "END"
        const val SAMPLER_PREFIX = "P:"
        const val PIDFILE_PREFIX = "F:"
        const val HEARTBEAT_PREFIX = "H:"
        const val PROCESS_COUNT_PREFIX = "N:"
        const val FIELD_COUNT = 9

        /** Lines written before the rated capacity moved to the state file. */
        const val LEGACY_FIELD_COUNT = 10

        /** The probe is a single `ps` + two `cat`s; anything slower is already a failure. */
        const val PROBE_TIMEOUT_MILLIS = 8_000L

        /** The start command polls for up to 4 s itself. */
        const val START_TIMEOUT_MILLIS = 12_000L
        const val STOP_TIMEOUT_MILLIS = 8_000L

        /** Upper bound of bytes consumed in one ingest, so a big backlog never stalls the UI. */
        const val MAX_BATCH_BYTES = 512 * 1024
    }
}
