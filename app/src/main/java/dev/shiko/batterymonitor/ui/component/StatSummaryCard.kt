package dev.shiko.batterymonitor.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shiko.batterymonitor.model.PowerSummary
import dev.shiko.batterymonitor.util.formatDuration
import dev.shiko.batterymonitor.util.formatWatts
import androidx.compose.ui.res.stringResource
import dev.shiko.batterymonitor.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Three-column summary for either screen-on or screen-off discharge.
 *
 * One [Card] holding a single row of three equal-width columns, mirroring the Scene reference:
 * a large blue value stacked on top of a small grey label.
 */
@Composable
fun StatSummaryCard(
    summary: PowerSummary,
    screenOn: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryColumn(
                    value = formatWatts(summary.averagePowerWatts),
                    label = stringResource(
                        if (screenOn) R.string.summary_screen_on_average_power
                        else R.string.summary_screen_off_average_power,
                    ),
                    modifier = Modifier.weight(1f),
                )
                SummaryColumn(
                    value = formatDuration(summary.usedMillis),
                    label = stringResource(if (screenOn) R.string.summary_screen_on_time else R.string.summary_screen_off_time),
                    modifier = Modifier.weight(1f),
                )
                SummaryColumn(
                    value = formatDuration(summary.estimatedRuntimeMillis),
                    label = stringResource(
                        if (screenOn) R.string.summary_screen_on_estimated_runtime
                        else R.string.summary_screen_off_estimated_runtime,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One of the three summary columns: blue value above, grey label below. */
@Composable
private fun SummaryColumn(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            modifier = Modifier.fillMaxWidth(),
            color = MiuixTheme.colorScheme.primary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
