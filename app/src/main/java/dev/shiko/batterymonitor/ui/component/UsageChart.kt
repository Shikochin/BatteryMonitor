package dev.shiko.batterymonitor.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shiko.batterymonitor.model.ChartSample
import dev.shiko.batterymonitor.model.ChartSegmentStyle
import dev.shiko.batterymonitor.util.formatAxisMinutes
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The hand-drawn "使用过程" curve.
 *
 * Solid blue segments while the screen was on, solid grey while it was off, dotted variants when
 * the recorder missed its planned tick — exactly like Scene's legend (see [ChartLegendDialog]).
 *
 * Geometry, from top to bottom:
 *  - a left gutter holding the `100%` … `20%` level labels,
 *  - the plot itself, with five horizontal grid lines (one per labelled level) and a vertical
 *    grid line every five minutes (the labelled ticks — `0`, `5m`, `15m`, `25m`, … — are a bit
 *    brighter, just like the reference),
 *  - the curve, and
 *  - a bottom strip holding the time labels.
 *
 * The level axis maps 20%…100% onto the plot, 100% at the top, and the whole drawing is derived
 * from the incoming size and [LocalDensity] so it scales with the screen without magic pixel
 * constants.
 *
 * Time runs left to right. While the session is still younger than [windowMillis] the axis starts
 * at the first recorded sample, so the curve grows from the left edge; once the session is longer
 * than the window, the oldest samples fall off the left and it becomes a sliding window that ends
 * at the newest sample. The tick labels always describe the full [windowMillis] horizon.
 */
@Composable
fun UsageChart(
    samples: List<ChartSample>,
    windowMillis: Long,
    startLevelPercent: Int,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val screenOnColor = colors.primary
    val screenOffColor = colors.onSurfaceVariantActions
    val gridColor = colors.dividerLine
    val labelColor = colors.onSurfaceVariantSummary

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = remember(labelColor) {
        TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal, color = labelColor)
    }

    // A dashed stroke is "dots" once the dashes are shorter than the round caps: the cap radius
    // (half the 3dp stroke) makes each tiny dash a circle, and the gap is whatever is left over.
    val strokeWidthPx = with(density) { 3.dp.toPx() }
    val dottedEffect = remember(density, strokeWidthPx) {
        PathEffect.dashPathEffect(
            intervals = floatArrayOf(0.1f, strokeWidthPx * 1.6f),
            phase = 0f,
        )
    }

    val orderedSamples = remember(samples) { samples.sortedBy { it.timestampMillis } }
    val windowMinutes = remember(windowMillis) { windowMinutes(windowMillis) }
    val tickMinutes = remember(windowMinutes) { labelledTicks(windowMinutes) }

    val levelLabels = remember(textMeasurer, labelStyle, density) {
        AxisLevels.map { level ->
            textMeasurer.measure(AnnotatedString("$level%"), labelStyle)
        }
    }
    val timeLabels = remember(textMeasurer, labelStyle, density, tickMinutes) {
        tickMinutes.map { minute ->
            textMeasurer.measure(AnnotatedString(formatAxisMinutes(minute)), labelStyle)
        }
    }

    Canvas(modifier = modifier) {
        val stroke = 3.dp.toPx()
        val hairline = 0.8.dp.toPx()
        val labelGapStart = 8.dp.toPx()
        val labelGapBottom = 6.dp.toPx()
        val topPadding = 2.dp.toPx()
        val endPadding = 2.dp.toPx()

        val levelLabelWidth = levelLabels.maxOf { it.size.width }.toFloat()
        val levelLabelHeight = levelLabels.maxOf { it.size.height }.toFloat()
        val timeLabelHeight = timeLabels.maxOf { it.size.height }.toFloat()

        val plotLeft = levelLabelWidth + labelGapStart
        val plotRight = (size.width - endPadding).coerceAtLeast(plotLeft + 1f)
        val plotTop = topPadding
        val plotBottom = (size.height - timeLabelHeight - labelGapBottom)
            .coerceAtLeast(plotTop + 1f)
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

        // The reference lets the vertical grid lines run slightly past the 100% / 20% lines, so the
        // curve has a little breathing room at both ends of the level axis. The inset is derived
        // from the label height so the top-most "100%" label (centred on its grid line) always fits
        // inside the canvas instead of being clipped by its top edge.
        val levelInset = (levelLabelHeight / 2f + 3.dp.toPx()).coerceAtMost(plotHeight / 4f)
        val levelTop = plotTop + levelInset
        val levelBottom = plotBottom - levelInset
        val levelHeight = (levelBottom - levelTop).coerceAtLeast(1f)

        fun yForLevel(level: Float): Float {
            val fraction = ((level - AxisMinLevel) / (AxisMaxLevel - AxisMinLevel))
                .coerceIn(0f, 1f)
            return levelBottom - fraction * levelHeight
        }

        fun xForMinute(minute: Int): Float {
            val fraction = (minute.toFloat() / windowMinutes.toFloat()).coerceIn(0f, 1f)
            return plotLeft + fraction * plotWidth
        }

        // --- Vertical grid (every five minutes, brighter on the labelled ticks) -----------------
        val labelledTicks = tickMinutes.toSet()
        var minute = 0
        while (minute <= windowMinutes) {
            val x = xForMinute(minute)
            val alpha = if (minute in labelledTicks) 0.55f else 0.22f
            drawLine(
                color = gridColor.copy(alpha = alpha),
                start = Offset(x, plotTop),
                end = Offset(x, plotBottom),
                strokeWidth = hairline,
            )
            minute += GridStepMinutes
        }

        // --- Horizontal grid (one line per labelled level) --------------------------------------
        for (level in AxisLevels) {
            val y = yForLevel(level.toFloat())
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = hairline,
            )
        }

        // --- Level labels (right aligned in the gutter, centred on their grid line) -------------
        AxisLevels.forEachIndexed { index, level ->
            val label = levelLabels[index]
            drawText(
                textLayoutResult = label,
                topLeft = Offset(
                    x = plotLeft - labelGapStart - label.size.width,
                    y = yForLevel(level.toFloat()) - label.size.height / 2f,
                ),
            )
        }

        // --- Time labels (centred under their tick) ---------------------------------------------
        tickMinutes.forEachIndexed { index, tick ->
            val label = timeLabels[index]
            drawText(
                textLayoutResult = label,
                topLeft = Offset(
                    x = xForMinute(tick) - label.size.width / 2f,
                    y = plotBottom + labelGapBottom,
                ),
            )
        }

        // --- The usage curve --------------------------------------------------------------------
        val newest = orderedSamples.lastOrNull()
        val oldest = orderedSamples.firstOrNull()
        val window = windowMillis.coerceAtLeast(1L)
        val recordedSpan = if (newest != null && oldest != null) {
            newest.timestampMillis - oldest.timestampMillis
        } else {
            0L
        }

        // What "0" on the time axis stands for. A session that is still younger than the window is
        // plotted from its very first sample, so the curve starts flush against the left edge and
        // grows rightwards; once the session outgrows the window this becomes the usual sliding
        // window whose right edge is the newest sample.
        val windowStart = when {
            oldest == null || newest == null -> 0L
            recordedSpan >= window -> newest.timestampMillis - window
            else -> oldest.timestampMillis
        }

        fun xForTimestamp(timestampMillis: Long): Float {
            val fraction = ((timestampMillis - windowStart).toFloat() / window.toFloat())
            return plotLeft + fraction.coerceIn(0f, 1f) * plotWidth
        }

        if (recordedSpan < window / MinimumCoverageDivisor) {
            // Not enough history yet — no samples at all, a single reading, or only the first few
            // ticks of a young session. A flat line at the last known level keeps the card looking
            // alive; it runs from the left edge up to the newest sample (a short stub for a
            // session that has only just begun, the full width when nothing is recorded at all).
            val level = (newest?.levelPercent ?: startLevelPercent).toFloat()
            val y = yForLevel(level)
            val endX = if (newest == null) {
                plotRight
            } else {
                maxOf(xForTimestamp(newest.timestampMillis), plotLeft + 24.dp.toPx())
            }
            drawLine(
                color = screenOnColor,
                start = Offset(plotLeft, y),
                end = Offset(endX, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        } else {
            val points = orderedSamples.map { sample ->
                Offset(
                    x = xForTimestamp(sample.timestampMillis),
                    y = yForLevel(sample.levelPercent.toFloat()),
                )
            }

            // A sample's style describes the stretch that *starts* at it, so consecutive segments
            // sharing a style are merged into one path: the dash pattern then flows across them
            // instead of restarting at every sample.
            var index = 0
            while (index < points.size - 1) {
                val style = orderedSamples[index].style
                val path = Path().apply {
                    moveTo(points[index].x, points[index].y)
                }
                while (index < points.size - 1 && orderedSamples[index].style == style) {
                    index++
                    path.lineTo(points[index].x, points[index].y)
                }

                val recorded = style == ChartSegmentStyle.ScreenOn ||
                    style == ChartSegmentStyle.MissedRecordScreenOn
                val dotted = style == ChartSegmentStyle.MissedRecordScreenOn ||
                    style == ChartSegmentStyle.MissedRecordScreenOff

                drawPath(
                    path = path,
                    color = if (recorded) screenOnColor else screenOffColor,
                    style = Stroke(
                        width = stroke,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = if (dotted) dottedEffect else null,
                    ),
                )
            }
        }
    }
}

/** Levels that get a label and a horizontal grid line, top to bottom. */
private val AxisLevels = listOf(100, 80, 60, 40, 20)

/** The level axis starts at [AxisMinLevel] and ends at [AxisMaxLevel]. */
private const val AxisMinLevel = 20f
private const val AxisMaxLevel = 100f

/** Distance between two vertical grid lines, in minutes. */
private const val GridStepMinutes = 5

/**
 * Below `window / this` worth of recorded history (4.5 min of a 45 min window) the chart shows the
 * flat "not enough data yet" line instead of a two-point curve that would barely be visible.
 */
private const val MinimumCoverageDivisor = 10

/** Length of the window in whole minutes, never zero. */
private fun windowMinutes(windowMillis: Long): Int =
    (windowMillis / 60_000L).toInt().coerceAtLeast(1)

/**
 * The labelled ticks of the time axis.
 *
 * The reference labels `0`, then `5m`, then every 10 minutes: a 45 minute window reads
 * `0 · 5m · 15m · 25m · 35m · 45m` and a 30 minute window reads `0 · 5m · 15m · 25m`.
 */
private fun labelledTicks(windowMinutes: Int): List<Int> {
    val ticks = mutableListOf(0)
    var minute = GridStepMinutes
    while (minute <= windowMinutes) {
        ticks += minute
        minute += GridStepMinutes * 2
    }
    if (ticks.size == 1) ticks += windowMinutes
    return ticks
}
