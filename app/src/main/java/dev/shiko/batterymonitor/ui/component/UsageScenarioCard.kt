package dev.shiko.batterymonitor.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shiko.batterymonitor.model.UsageScenario
import dev.shiko.batterymonitor.util.formatCelsius
import dev.shiko.batterymonitor.util.formatDuration
import dev.shiko.batterymonitor.util.formatMilliAmps
import dev.shiko.batterymonitor.util.formatWatts
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Voltage used to turn watts into milliamps when no battery snapshot is available yet. */

/**
 * Card 3 — "使用场景": one row per app with icon, label, AVG/MAX telemetry and duration.
 *
 * Rows are always ordered by average power, highest first ("使用场景应该按照 W 数排序"). The header
 * carries a refresh button plus a unit switch that flips the AVG readout between watts and
 * milliamps (Scene's W ↔ mA toggle); the switch is local to this card and the watts are converted
 * with the live battery voltage.
 *
 * The breakdown comes exclusively from Shizuku now, so without the grant the card shows a
 * "去授权" prompt instead of a list.
 *
 * @param scenarios the app breakdown, in whatever order the repository produced it.
 * @param needsShizuku whether the Shizuku permission still has to be granted.
 * @param loading whether the breakdown is currently being computed.
 * @param onRequestShizuku asks Shizuku for the permission ("去授权").
 * @param onRefresh re-reads the scenario list.
 */
@Composable
fun UsageScenarioCard(
    scenarios: List<UsageScenario>,
    needsShizuku: Boolean,
    loading: Boolean,
    onRequestShizuku: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var powerInMilliAmps by remember { mutableStateOf(false) }
    var sortDescending by remember { mutableStateOf(true) }

    // Always ordered by draw (Scene's default is highest power first); the sort action flips it.
    val sortedScenarios = remember(scenarios, sortDescending) {
        if (sortDescending) {
            scenarios.sortedByDescending { it.averagePowerWatts }
        } else {
            scenarios.sortedBy { it.averagePowerWatts }
        }
    }

    Card(
        modifier = modifier,
        // The header row is centred against 40dp icon buttons, so the title would sit ~8dp lower
        // than on the other cards; trimming the top inset keeps all card titles on one line.
        insideMargin = PaddingValues(top = 8.dp, bottom = 16.dp),
    ) {
        ScenarioHeader(
            powerInMilliAmps = powerInMilliAmps,
            sortDescending = sortDescending,
            onToggleUnits = { powerInMilliAmps = !powerInMilliAmps },
            onToggleSort = { sortDescending = !sortDescending },
        )

        when {
            needsShizuku -> ShizukuRequestState(onRequestShizuku = onRequestShizuku)
            sortedScenarios.isEmpty() -> EmptyState(loading = loading, onRefresh = onRefresh)
            else -> sortedScenarios.forEach { scenario ->
                ScenarioRow(
                    scenario = scenario,
                    powerInMilliAmps = powerInMilliAmps,
                )
            }
        }
    }
}

/**
 * Title plus the two header actions, matching the reference order:
 * left = the circular "↻"-looking glyph, which switches the per-app readout between W and mA;
 * right = the list glyph, which flips the ordering between descending and ascending power.
 */
@Composable
private fun ScenarioHeader(
    powerInMilliAmps: Boolean,
    sortDescending: Boolean,
    onToggleUnits: () -> Unit,
    onToggleSort: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "使用场景",
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onToggleUnits) {
            Icon(
                imageVector = MiuixIcons.Regular.Refresh,
                contentDescription = if (powerInMilliAmps) "切换为 W 单位" else "切换为 mA 单位",
                modifier = Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onToggleSort) {
            Icon(
                imageVector = MiuixIcons.Regular.Sort,
                contentDescription = if (sortDescending) "当前按功耗从高到低，点击改为从低到高" else "当前按功耗从低到高，点击改为从高到低",
                modifier = Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

/** One app row: 44dp icon, label + AVG/MAX line, trailing duration. */
@Composable
private fun ScenarioRow(
    scenario: UsageScenario,
    powerInMilliAmps: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScenarioIcon(icon = scenario.icon)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = scenario.label,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "AVG: ${formatAverage(scenario.averagePowerWatts, scenario.averageCurrentMilliAmps, powerInMilliAmps)}, " +
                    "${formatCelsius(scenario.averageTemperatureCelsius, 0)}   " +
                    "MAX: ${formatCelsius(scenario.maxTemperatureCelsius, 0)}",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = formatDuration(scenario.durationMillis),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

/**
 * Formats an average draw either as watts or, when the mA switch is on, as milliamps using the
 * live battery voltage (Scene shows whole milliamps).
 */
private fun formatAverage(
    averagePowerWatts: Float,
    averageCurrentMilliAmps: Float,
    powerInMilliAmps: Boolean,
): String = if (powerInMilliAmps) {
    // Scene shows the measured average current in mA mode, not a W/V conversion.
    formatMilliAmps(averageCurrentMilliAmps)
} else {
    formatWatts(averagePowerWatts)
}

/** Rounded app icon, with a neutral placeholder when the launcher icon could not be loaded. */
@Composable
private fun ScenarioIcon(
    icon: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = modifier
                .size(44.dp)
                .clip(shape),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier = modifier
                .size(44.dp)
                .clip(shape)
                .background(MiuixTheme.colorScheme.surfaceContainerHighest),
        )
    }
}

/** Shown when the Shizuku permission has not been granted yet. */
@Composable
private fun ShizukuRequestState(
    onRequestShizuku: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "需要 Shizuku 权限才能统计应用耗电",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(
            text = "去授权",
            onClick = onRequestShizuku,
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

/** Shown when the permission is granted but no app has been in the foreground yet. */
@Composable
private fun EmptyState(
    loading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (loading) "正在统计…" else "最近 45 分钟内没有应用使用记录",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        if (!loading) {
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(text = "刷新", onClick = onRefresh, colors = ButtonDefaults.textButtonColors())
        }
    }
}
