package dev.shiko.batterymonitor.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shiko.batterymonitor.model.SessionRecord
import dev.shiko.batterymonitor.util.formatDuration
import dev.shiko.batterymonitor.util.formatWattHours
import dev.shiko.batterymonitor.util.formatWatts
import androidx.compose.ui.res.stringResource
import dev.shiko.batterymonitor.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import kotlin.math.abs
import java.util.Date
import java.util.Locale

/** How many sessions the dialog shows at most (the newest ones). */
private const val MAX_HISTORY_ROWS = 10

/**
 * The "历史记录" dialog opened from the top bar's history action.
 *
 * Lists the most recent monitoring sessions, newest first: start time, discharge rate in
 * percent-per-hour and the battery life that rate implies. Every row can be deleted on its own, or
 * the whole list can be cleared at once.
 *
 * @param show whether the dialog is visible.
 * @param records finished sessions, as produced by the repository.
 * @param onDismiss called when the dialog asks to be closed.
 * @param onDeleteHistory removes a single session, identified by its [SessionRecord.startedAtMillis].
 * @param onClearHistory removes every stored session.
 */
@Composable
fun SessionHistoryDialog(
    show: Boolean,
    records: List<SessionRecord>,
    onDismiss: () -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onClearHistory: () -> Unit,
) {
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val visibleRecords = remember(records) {
        records.sortedByDescending { it.startedAtMillis }.take(MAX_HISTORY_ROWS)
    }

    OverlayDialog(
        show = show,
        title = stringResource(R.string.history_title),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (visibleRecords.isEmpty()) {
                Text(
                    text = stringResource(R.string.history_empty),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    visibleRecords.forEachIndexed { index, record ->
                        HistoryRow(
                            record = record,
                            startLabel = timeFormat.format(Date(record.startedAtMillis)),
                            onDelete = { onDeleteHistory(record.startedAtMillis) },
                        )
                        if (index != visibleRecords.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (visibleRecords.isNotEmpty()) {
                    TextButton(
                        text = stringResource(R.string.history_clear),
                        onClick = onClearHistory,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(),
                    )
                }
                TextButton(
                    text = stringResource(R.string.action_close),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** One finished session: start time + telemetry on the left, duration and delete on the right. */
@Composable
private fun HistoryRow(
    record: SessionRecord,
    startLabel: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = startLabel,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = sessionSummary(record),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatDuration(record.durationMillis),
            modifier = Modifier.padding(start = 12.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
            maxLines = 1,
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier.padding(start = 4.dp),
            minWidth = 32.dp,
            minHeight = 32.dp,
        ) {
            Icon(
                imageVector = MiuixIcons.Light.Delete,
                contentDescription = stringResource(R.string.history_delete),
                modifier = Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * One line with everything a session is judged by, as bare figures:
 * `-2.1%/h / 1.45W / 0.3Wh / 47h36m` (discharge rate, average power, energy, implied runtime).
 */
private fun sessionSummary(record: SessionRecord): String {
    val hours = record.durationMillis / 3_600_000.0
    val deltaPercent = (record.endLevelPercent - record.startLevelPercent).toDouble()
    val ratePerHour = if (hours > 0.0) deltaPercent / hours else 0.0
    val rateLabel = if (abs(ratePerHour) < 0.05) {
        "0.0%/h"
    } else {
        String.format(Locale.US, "%+.1f%%/h", ratePerHour)
    }
    val runtimeLabel = if (ratePerHour < -0.05) {
        formatDuration((100.0 / -ratePerHour * 3_600_000.0).toLong())
    } else {
        "--"
    }
    // Bare figures, no labels: -x%/h / xW / xWh / xh
    return "$rateLabel / ${formatWatts(record.averagePowerWatts)} / " +
        "${formatWattHours(record.energyWattHours)} / $runtimeLabel"
}
