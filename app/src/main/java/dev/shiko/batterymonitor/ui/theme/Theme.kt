package dev.shiko.batterymonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Miuix (HyperOS style) theme for the app.
 *
 * The reference design is a dark, almost black surface with floating cards, so we keep the Miuix
 * dark scheme untouched — its `surface` is pure black and `surfaceContainer` is #242424, which is
 * exactly the relationship the Scene screen uses.
 */
@Composable
fun BatteryMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MiuixTheme(
        colors = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
