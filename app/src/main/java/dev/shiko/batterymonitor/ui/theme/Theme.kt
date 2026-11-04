package dev.shiko.batterymonitor.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Miuix (HyperOS style) theme driven by **monet** — the wallpaper-derived palette the system
 * exposes (Material You). On Android 12+ Miuix reads the platform's dynamic roles directly, and
 * below that it falls back to the default Miuix palette, so the app always follows the system's
 * light/dark mode and accent colour instead of hard-coding blue.
 */
@Composable
fun BatteryMonitorTheme(
    content: @Composable () -> Unit,
) {
    // MonetSystem follows the system light/dark setting by itself (isDark stays null).
    val controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.MonetSystem) }
    MiuixTheme(controller = controller, content = content)
}
