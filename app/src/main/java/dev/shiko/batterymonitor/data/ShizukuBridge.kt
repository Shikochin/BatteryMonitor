package dev.shiko.batterymonitor.data

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import rikka.shizuku.Shizuku
import moe.shizuku.server.IShizukuService

/**
 * Thin, crash-proof wrapper around the Shizuku API.
 *
 * Shizuku lets an app talk to the "shell" (uid 2000) / root service that the user started. Direct
 * root via KernelSU/Magisk is handled by [DirectRootBridge] and is preferred for each command.
 *
 * Every call is defensive: the Shizuku binder can disappear at any moment (user stops the service,
 * Shizuku is force-stopped, the device reboots) and the API throws `IllegalStateException` /
 * `RemoteException` in those cases. [exec] never throws and returns `null` on any failure.
 *
 * `Shizuku.newProcess(...)` is *private* in API 13.1.5, so the same [IShizukuService] call that the
 * library performs internally is made through the AIDL interface (transitive `aidl` artifact).
 */
internal object ShizukuBridge {

    /** Request code passed to [Shizuku.requestPermission]; echoed back in the result callback. */
    private const val PERMISSION_REQUEST_CODE = 0

    /** Killing a stuck `dumpsys` after this long keeps the sampling loop responsive. */
    private const val EXEC_TIMEOUT_MILLIS = 15_000L

    /** Give the reader threads this long to drain once the process is gone. */
    private const val STREAM_JOIN_MILLIS = 2_000L

    @Volatile
    private var pendingPermissionListener: Shizuku.OnRequestPermissionResultListener? = null

    /** `true` when the Shizuku (or Sui) service is running and its binder is alive. */
    fun isServiceAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /**
     * The uid the Shizuku service runs as: `0` means it can read `/sys/class/power_supply` directly
     * (Sui via KernelSU/Magisk), `2000` means shell-only. `null` when the service is not running.
     */
    fun serverUid(): Int? {
        if (DirectRootBridge.isAvailable()) return 0
        if (!isServiceAvailable()) return null
        return try {
            Shizuku.getUid()
        } catch (_: Throwable) {
            null
        }
    }

    /** `true` when the service is alive and this app has been granted Shizuku access. */
    fun hasPermission(): Boolean {
        if (DirectRootBridge.isAvailable()) return true
        if (!isServiceAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Shows the Shizuku permission dialog.
     *
     * The result listener is registered right before the request and removed again in the
     * callback; [onResult] is always invoked exactly once, with `false` whenever the service is
     * unavailable or the request cannot be made.
     */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!isServiceAvailable()) {
            onResult(false)
            return
        }
        if (hasPermission()) {
            onResult(true)
            return
        }

        removePendingListener()

        lateinit var listener: Shizuku.OnRequestPermissionResultListener
        listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            removeListener(listener)
            onResult(grantResult == PackageManager.PERMISSION_GRANTED)
        }

        val listenerAdded = try {
            Shizuku.addRequestPermissionResultListener(listener)
            pendingPermissionListener = listener
            true
        } catch (_: Throwable) {
            false
        }
        if (!listenerAdded) {
            onResult(false)
            return
        }

        val requested = try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        } catch (_: Throwable) {
            false
        }
        if (!requested) {
            removeListener(listener)
            onResult(false)
        }
    }

    /**
     * Runs a shell command through Shizuku and returns its combined stdout (or stderr when stdout
     * is empty). Blocking — call from a background dispatcher. Returns `null` on any failure,
     * including a timeout, so a broken Shizuku never breaks the caller.
     */
    fun exec(command: String, timeoutMillis: Long = EXEC_TIMEOUT_MILLIS): String? {
        DirectRootBridge.exec(command, timeoutMillis)?.let { return it }
        if (!hasPermission()) return null
        return try {
            execute(command, timeoutMillis)
        } catch (_: Throwable) {
            null
        }
    }

    private fun execute(command: String, timeoutMillis: Long): String? {
        val binder = Shizuku.getBinder() ?: return null
        val service = IShizukuService.Stub.asInterface(binder)
        val remote = service.newProcess(arrayOf("sh", "-c", command), null, null) ?: return null

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        // Both pipes must be drained concurrently: `dumpsys batterystats` writes ~150 KiB and a
        // full stderr buffer would deadlock the child while we are still reading stdout.
        val stdoutReader = StreamReader(remote.inputStream) { stdout.append(it) }
        val stderrReader = StreamReader(remote.errorStream) { stderr.append(it) }
        stdoutReader.start()
        stderrReader.start()

        val finished = try {
            remote.waitForTimeout(timeoutMillis, TIME_UNIT_MILLIS)
        } catch (_: Throwable) {
            false
        }
        if (!finished) {
            try {
                remote.destroy()
            } catch (_: Throwable) {
                // Nothing left to clean up.
            }
        }
        stdoutReader.join(STREAM_JOIN_MILLIS)
        stderrReader.join(STREAM_JOIN_MILLIS)

        if (!finished) return null
        val output = stdout.toString()
        return if (output.isNotEmpty()) output else stderr.toString().takeIf { it.isNotEmpty() }
    }

    private fun removePendingListener() {
        val listener = pendingPermissionListener ?: return
        pendingPermissionListener = null
        removeListener(listener)
    }

    private fun removeListener(listener: Shizuku.OnRequestPermissionResultListener) {
        if (pendingPermissionListener === listener) pendingPermissionListener = null
        try {
            Shizuku.removeRequestPermissionResultListener(listener)
        } catch (_: Throwable) {
            // Service died in the meantime; nothing to remove.
        }
    }

    /** Drains one of the remote process pipes on its own thread. */
    private class StreamReader(
        private val descriptor: ParcelFileDescriptor?,
        private val sink: (String) -> Unit,
    ) : Thread() {

        init {
            isDaemon = true
            name = "shizuku-exec-reader"
        }

        override fun run() {
            val pipe = descriptor ?: return
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pipe).use { stream ->
                    val text = stream.bufferedReader(Charsets.UTF_8).readText()
                    if (text.isNotEmpty()) sink(text)
                }
            } catch (_: Throwable) {
                // Pipe closed because the process was destroyed or the service died.
            }
        }
    }

    /** `IRemoteProcess.waitForTimeout` takes the `TimeUnit.toString()` form. */
    private const val TIME_UNIT_MILLIS = "MILLISECONDS"
}
