package dev.shiko.batterymonitor.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps the root sampler alive when the app process is not.
 *
 * The daemon itself is a child of the Shizuku server, so it survives the app being frozen or
 * killed — but it dies with the Shizuku server (a reboot, a wireless-debugging reconnect, a
 * force-stop) and nothing in the app can notice while the process is gone. A persisted periodic
 * job wakes the app every 15 minutes, re-checks the process table and starts the sampler again if
 * needed. Jobs are exempt from background-start restrictions and survive reboots (hence
 * `RECEIVE_BOOT_COMPLETED` in the manifest).
 */
object DaemonWatchdog {

    private const val JOB_ID = 0x0B47

    /** JobScheduler's minimum period; also a sane battery/keep-alive trade-off. */
    private const val INTERVAL_MILLIS = 15L * 60_000L

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        // Re-scheduling an existing job would reset its period; leave it alone.
        if (runCatching { scheduler.getPendingJob(JOB_ID) }.getOrNull() != null) return

        val job = JobInfo.Builder(JOB_ID, ComponentName(context, DaemonWatchdogService::class.java))
            .setPeriodic(INTERVAL_MILLIS)
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .build()
        runCatching { scheduler.schedule(job) }
            .onFailure { Log.w("BatteryDaemon", "watchdog schedule failed: ${it.message}") }
    }
}

/**
 * Runs [RootMonitorDaemon.ensureRunning] on the watchdog schedule.
 *
 * `ensureRunning` is idempotent and only returns after verifying the real process table, so a
 * duplicate can never be created by a race with the foreground app.
 */
class DaemonWatchdogService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            // A user who paused recording stays paused across reboots: the flag is persisted, so
            // the watchdog never revives the sampler behind the user's back (and reaps one that
            // outlived the pause, e.g. a sampler started by an older build before the flag was set).
            val paused = runCatching { SessionStore(applicationContext).loadRecordingPaused() }
                .getOrDefault(false)
            if (paused) {
                val stopped = runCatching { RootMonitorDaemon(applicationContext).stop() }
                    .getOrDefault(false)
                Log.d("BatteryDaemon", "watchdog: recording paused, stopped=$stopped")
            } else {
                val running = runCatching { RootMonitorDaemon(applicationContext).ensureRunning() }
                    .getOrDefault(false)
                Log.d("BatteryDaemon", "watchdog: running=$running")
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
