package dev.shiko.batterymonitor.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.shiko.batterymonitor.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The "说明提示" help sheet behind the `?` next to the "使用过程" title.
 *
 * It explains the four line styles of [UsageChart]: solid blue = screen on, solid grey = screen
 * off, and the two dotted variants used when the recorder missed a planned tick. The wording is
 * Scene's, with the app name swapped for "本应用".
 *
 * @param show Whether the dialog is visible.
 * @param onDismiss Called when the sheet is dismissed — by the "我知道了" button, a tap outside or
 *   the system back gesture.
 */
@Composable
fun ChartLegendDialog(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    OverlayDialog(
        show = show,
        title = stringResource(R.string.legend_title),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LegendRow(color = colors.primary, dotted = false, label = stringResource(R.string.legend_screen_on))
            LegendRow(color = colors.primary.inverted(), dotted = false, label = stringResource(R.string.legend_screen_off))
            LegendRow(color = colors.primary, dotted = true, label = stringResource(R.string.legend_missed_screen_on))
            LegendRow(color = colors.primary.inverted(), dotted = true, label = stringResource(R.string.legend_missed_screen_off))

            Spacer(Modifier.height(16.dp))
            HintParagraph(stringResource(R.string.legend_hint_screen_off))
            Spacer(Modifier.height(10.dp))
            HintParagraph(stringResource(R.string.legend_hint_missed))

            Spacer(Modifier.height(20.dp))
            TextButton(
                text = stringResource(R.string.legend_got_it),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * One legend entry: a 32dp sample of the line style plus its description.
 */
@Composable
private fun LegendRow(
    color: Color,
    dotted: Boolean,
    label: String,
) {
    val density = LocalDensity.current
    val sampleStrokePx = with(density) { 2.dp.toPx() }
    val dottedEffect = remember(sampleStrokePx) {
        PathEffect.dashPathEffect(
            intervals = floatArrayOf(0.1f, sampleStrokePx * 1.8f),
            phase = 0f,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            modifier = Modifier
                .width(32.dp)
                .height(12.dp),
        ) {
            val centerY = size.height / 2f
            drawLine(
                color = color,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = sampleStrokePx,
                cap = StrokeCap.Round,
                pathEffect = if (dotted) dottedEffect else null,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            fontSize = 16.sp,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/**
 * One explanation paragraph, sitting in a subtly tinted rounded box like in the reference.
 */
@Composable
private fun HintParagraph(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        color = MiuixTheme.colorScheme.onSurfaceSecondary,
    )
}
