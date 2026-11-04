package dev.shiko.batterymonitor.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shiko.batterymonitor.model.BatterySnapshot
import dev.shiko.batterymonitor.model.PowerUiState
import dev.shiko.batterymonitor.util.formatCelsius
import dev.shiko.batterymonitor.util.formatVoltage
import dev.shiko.batterymonitor.util.formatWatts
import dev.shiko.batterymonitor.util.formatWattHours
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Card 1 — "使用过程": header (title + help + battery level), the curve, and the telemetry row
 * (energy / temperature / voltage / charging state).
 *
 * The `?` next to the title opens [ChartLegendDialog]; that bit of state is local because the card
 * is purely a presentational widget and nobody else needs to know about the sheet.
 */
@Composable
fun UsageProcessCard(
    state: PowerUiState,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val snapshot = state.snapshot
    val levelPercent = snapshot?.levelPercent ?: state.startLevelPercent
    var showLegend by remember { mutableStateOf(false) }
    // Package -> launcher icon, so the chart can draw the same icons as the scenario list.
    val chartIcons = remember(state.scenarios) {
        buildMap {
            for (scenario in state.scenarios) {
                val icon = scenario.icon ?: continue
                put(scenario.packageName, icon)
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "使用过程",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
            )
            Spacer(Modifier.width(6.dp))
            HelpButton(onClick = { showLegend = true })
            Spacer(Modifier.weight(1f))
            Text(
                text = "$levelPercent%",
                fontSize = 18.sp,
                color = colors.onSurfaceSecondary,
            )
        }

        Spacer(Modifier.height(14.dp))

        UsageChart(
            samples = state.chartSamples,
            startLevelPercent = state.startLevelPercent,
            icons = chartIcons,
            modifier = Modifier
                .fillMaxWidth()
                .height(205.dp),
        )

        Spacer(Modifier.height(12.dp))

        TelemetryRow(snapshot = snapshot, fullCapacityWattHours = state.fullCapacityWattHours)
    }

    // Rendered through the Miuix scaffold's popup host, so it adds no layout node of its own.
    ChartLegendDialog(show = showLegend, onDismiss = { showLegend = false })
}

/**
 * The small grey "?" that opens the legend sheet.
 */
@Composable
private fun HelpButton(onClick: () -> Unit) {
    Text(
        text = "?",
        modifier = Modifier
            .clickable(onClickLabel = "说明提示", onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/**
 * The four evenly divided telemetry cells under the curve.
 */
@Composable
private fun TelemetryRow(
    snapshot: BatterySnapshot?,
    fullCapacityWattHours: Float?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TelemetryCell(
            icon = TelemetryBattery,
            // Scene shows the pack's full capacity (health-adjusted), not the charge left in it.
            text = fullCapacityWattHours
                ?.let { formatWattHours(it) }
                ?: "--",
        )
        TelemetryCell(
            icon = TelemetryFlame,
            text = snapshot?.let { formatCelsius(it.temperatureCelsius) } ?: "--",
        )
        TelemetryCell(
            icon = TelemetryBolt,
            text = snapshot?.let { formatVoltage(it.voltageVolts) } ?: "--",
        )
        TelemetryCell(
            icon = TelemetryPlug,
            text = livePowerLabel(snapshot),
        )
    }
}

/**
 * One telemetry cell: an 18dp glyph plus its value, centred inside an equal share of the row.
 */
@Composable
private fun RowScope.TelemetryCell(
    icon: ImageVector,
    text: String,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            color = colors.onSurfaceSecondary,
            maxLines = 1,
        )
    }
}

/**
 * Live power draw, signed by the current direction: negative while the battery discharges
 * (`-2.13W`), positive while it charges (`1.85W`), exactly like Scene's counter.
 */
private fun livePowerLabel(snapshot: BatterySnapshot?): String {
    if (snapshot == null) return "--"
    val magnitude = formatWatts(snapshot.powerWatts)
    return if (snapshot.charging) magnitude else "-$magnitude"
}
