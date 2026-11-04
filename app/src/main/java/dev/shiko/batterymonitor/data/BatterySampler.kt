package dev.shiko.batterymonitor.data

import android.content.Context
import android.os.PowerManager

/**
 * Screen-interactive state for the chart segments.
 *
 * The app is Shizuku-only: every battery *number* comes from `dumpsys battery`, so the only thing
 * still worth reading through the public SDK here is whether the screen is on (it decides between
 * solid and dotted chart segments).
 */
internal class BatterySampler(context: Context) {

    private val powerManager: PowerManager? =
        context.applicationContext.getSystemService(PowerManager::class.java)

    fun isScreenOn(): Boolean = powerManager?.isInteractive ?: true
}
