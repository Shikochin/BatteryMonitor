package dev.shiko.batterymonitor.data

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import kotlin.math.abs

/**
 * The two public-API battery reads the repository still needs:
 *
 *  - screen-interactive state (it decides between solid and dotted chart segments),
 *  - `BATTERY_PROPERTY_CURRENT_NOW`, a **public** instantaneous-current source that works without
 *    root. Its unit is unreliable too (this ROM returns milliamps through the microamp property),
 *    which is exactly what [CurrentCalibrator] resolves.
 */
internal class BatterySampler(context: Context) {

    private val appContext: Context = context.applicationContext
    private val powerManager: PowerManager? =
        appContext.getSystemService(PowerManager::class.java)
    private val batteryManager: BatteryManager? =
        appContext.getSystemService(BatteryManager::class.java)

    fun isScreenOn(): Boolean = powerManager?.isInteractive ?: true

    /**
     * Raw `BATTERY_PROPERTY_CURRENT_NOW` value (µA by contract, but not on every ROM).
     * `Int.MIN_VALUE`, `0` and absurd magnitudes are rejected so the caller can fall through.
     */
    fun readCurrentNowRaw(): Long? {
        val raw = runCatching {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull() ?: return null
        if (raw == Int.MIN_VALUE || raw == 0) return null
        if (abs(raw.toLong()) > MAX_PLAUSIBLE_CURRENT_MICRO_AMPS) return null
        return raw.toLong()
    }

    private companion object {
        /** 20 A in µA: anything larger is a broken reading. */
        const val MAX_PLAUSIBLE_CURRENT_MICRO_AMPS = 20_000_000L
    }
}
