package dev.shiko.batterymonitor.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shiko.batterymonitor.model.ChartSample
import dev.shiko.batterymonitor.model.ChartSegmentStyle
import dev.shiko.batterymonitor.util.formatAxisMinutes
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The hand-drawn "使用过程" curve, aligned with Scene's `PowerStatView`:
 *
 *  - **x axis auto-scales to the session**: the span is picked from Scene's ladder
 *    (10, 15, 30, 45, 60, 90, …, 1440 minutes) so the recorded session fills the chart instead of
 *    being squeezed into / stretched over a fixed 45-minute window. `x = (t - first) / 60 000 *
 *    pixels-per-minute`. Screen-off gaps are compressed to one sampling step so the axis matches
 *    the recorded usage duration shown below the chart.
 *  - **y axis is 0 %…100 %**: a grid line every 10 % and a label every 20 % (`20%` … `100%`), the
 *    bottom of the plot being the 0 % line — Scene loops 0..101 and labels only every other line.
 *  - **app icons are bucketed into 32 px time columns** like Scene: every foreground stretch longer
 *    than 3 s puts its app into each column it touches, the icons of a column stack upwards from the
 *    0 % baseline in 34 px steps, and each icon is 32 px with a rounded-rect outline. Because icons
 *    stack vertically they never overlap each other, no matter how fast apps are switched. Within a
 *    column the higher icon has the larger average wattage.
 *
 * A tap anywhere on the chart hides / shows the icons; the level curve itself is unchanged.
 *
 * Solid blue segments while the screen was on, solid grey while it was off, dotted variants when
 * the recorder missed its planned tick — exactly like Scene's legend (see [ChartLegendDialog]).
 */
@Composable
fun UsageChart(
    samples: List<ChartSample>,
    startLevelPercent: Int,
    modifier: Modifier = Modifier,
    icons: Map<String, ImageBitmap> = emptyMap(),
) {
    val colors = MiuixTheme.colorScheme
    val screenOnColor = colors.primary
    // Use the exact RGB inverse of the active blue so screen-on and screen-off remain distinct
    // even when Monet changes the accent palette.
    val screenOffColor = colors.primary.inverted()
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
    // The usage summary counts recorded 3 s ticks. A screen-off marker is kept so the curve can
    // show the grey state, but the wall-clock interval until the next on-screen sample must not
    // expand the usage axis by the whole time the device was asleep.
    val chartSamples = remember(orderedSamples) { compressScreenOffGaps(orderedSamples) }
    val span = remember(chartSamples) { axisSpan(chartSamples) }
    val tickPlan = remember(span) { tickPlan(span.minutes, span.labelCount) }
    val tickLabels = remember(tickPlan, textMeasurer, labelStyle) {
        tickPlan.labelledMinutes.map { textMeasurer.measure(AnnotatedString(formatAxisMinutes(it)), labelStyle) }
    }
    val levelLabels = remember(textMeasurer, labelStyle, density) {
        LevelLabelValues.map { level ->
            textMeasurer.measure(AnnotatedString("$level%"), labelStyle)
        }
    }

    var iconsVisible by rememberSaveable { mutableStateOf(true) }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { iconsVisible = !iconsVisible }
        },
    ) {
        val stroke = 3.dp.toPx()
        val hairline = 0.8.dp.toPx()
        val labelGapStart = 8.dp.toPx()
        val labelGapBottom = 6.dp.toPx()
        val topPadding = 2.dp.toPx()
        val endPadding = 2.dp.toPx()

        val levelLabelWidth = levelLabels.maxOf { it.size.width }.toFloat()
        val levelLabelHeight = levelLabels.maxOf { it.size.height }.toFloat()
        val timeLabelHeight = tickLabels.maxOf { it.size.height }.toFloat()

        val plotLeft = levelLabelWidth + labelGapStart
        val plotRight = (size.width - endPadding).coerceAtLeast(plotLeft + 1f)
        val plotTop = topPadding
        val plotBottom = (size.height - timeLabelHeight - labelGapBottom)
            .coerceAtLeast(plotTop + 1f)
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

        // A little breathing room above 100 % / below 0 % so the outermost labels and grid lines
        // are not clipped; derived from the label height instead of a magic pixel value.
        val levelInset = (levelLabelHeight / 2f + 3.dp.toPx()).coerceAtMost(plotHeight / 4f)
        val levelTop = plotTop + levelInset
        val levelBottom = plotBottom - levelInset
        val levelHeight = (levelBottom - levelTop).coerceAtLeast(1f)

        fun yForLevel(level: Float): Float {
            val fraction = ((level - AxisMinLevel) / (AxisMaxLevel - AxisMinLevel))
                .coerceIn(0f, 1f)
            return levelBottom - fraction * levelHeight
        }

        // Scene's x mapping: minutes since the first sample, scaled by the auto-fitted span.
        val firstTimestampMillis = chartSamples.firstOrNull()?.timestampMillis ?: 0L
        val pixelsPerMinute = plotWidth / span.minutes.toFloat()
        // Distance from the left edge of the plot, without the gutter (`plotLeft`).
        fun xOffsetForTimestamp(timestampMillis: Long): Float =
            (timestampMillis - firstTimestampMillis) / 60_000f * pixelsPerMinute

        fun xForTimestamp(timestampMillis: Long): Float = plotLeft + xOffsetForTimestamp(timestampMillis)

        // --- Vertical grid: one line per tick interval, brighter where a label sits --------------
        tickPlan.gridMinutes.forEach { minute ->
            val x = plotLeft + minute * pixelsPerMinute
            drawLine(
                color = gridColor.copy(alpha = 0.22f),
                start = Offset(x, plotTop),
                end = Offset(x, plotBottom),
                strokeWidth = hairline,
            )
        }
        tickPlan.labels.forEachIndexed { labelIndex, (_, minute) ->
            val x = plotLeft + minute * pixelsPerMinute
            drawLine(
                color = gridColor.copy(alpha = 0.55f),
                start = Offset(x, plotTop),
                end = Offset(x, plotBottom),
                strokeWidth = hairline,
            )
            val label = tickLabels.getOrNull(labelIndex) ?: return@forEachIndexed
            drawText(
                textLayoutResult = label,
                topLeft = Offset(
                    x = x - label.size.width / 2f,
                    y = plotBottom + labelGapBottom,
                ),
            )
        }

        // --- Horizontal grid: a line every 10 % (Scene), labels every 20 % ---------------------
        var level = 0
        while (level <= 100) {
            val y = yForLevel(level.toFloat())
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = hairline,
            )
            level += 10
        }

        // --- Level labels (right aligned in the gutter, centred on their grid line) -------------
        LevelLabelValues.forEachIndexed { index, value ->
            val label = levelLabels[index]
            drawText(
                textLayoutResult = label,
                topLeft = Offset(
                    x = plotLeft - labelGapStart - label.size.width,
                    y = yForLevel(value.toFloat()) - label.size.height / 2f,
                ),
            )
        }

        clipRect(left = plotLeft, top = plotTop, right = plotRight, bottom = plotBottom) {
            if (chartSamples.size < 2) {
                // Nothing recorded yet: a flat line at the last known level keeps the card alive.
                val level = (chartSamples.lastOrNull()?.levelPercent ?: startLevelPercent).toFloat()
                drawLine(
                    color = screenOnColor,
                    start = Offset(plotLeft, yForLevel(level)),
                    end = Offset(plotRight, yForLevel(level)),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            } else {
                val points = ArrayList<Offset>(chartSamples.size)
                for (sample in chartSamples) {
                    points += Offset(
                        x = xForTimestamp(sample.timestampMillis),
                        y = yForLevel(sample.levelPercent.toFloat()),
                    )
                }

                // A sample's style describes the stretch that *starts* at it, so consecutive
                // segments sharing a style are merged into one path: the dash pattern then flows
                // across them instead of restarting at every sample.
                var index = 0
                while (index < points.size - 1) {
                    val style = chartSamples[index].style
                    val path = Path().apply {
                        moveTo(points[index].x, points[index].y)
                    }
                    while (index < points.size - 1 && chartSamples[index].style == style) {
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

            if (iconsVisible && icons.isNotEmpty()) {
                drawAppIcons(
                    samples = chartSamples,
                    icons = icons,
                    baselineY = yForLevel(0f),
                    plotTop = plotTop,
                    plotLeft = plotLeft,
                    plotRight = plotRight,
                    outlineColor = labelColor,
                    xOffsetForTimestamp = ::xOffsetForTimestamp,
                )
            }
        }
    }
}

/**
 * Draws the app icons exactly like Scene: 32 px time columns, icons stacked upwards from the 0 %
 * baseline in 34 px steps, each 32 px with a rounded-rect outline.
 */
private fun DrawScope.drawAppIcons(
    samples: List<ChartSample>,
    icons: Map<String, ImageBitmap>,
    baselineY: Float,
    plotTop: Float,
    plotLeft: Float,
    plotRight: Float,
    outlineColor: Color,
    xOffsetForTimestamp: (Long) -> Float,
) {
    // Foreground stretches: consecutive samples of the same package, longer than 3 s.
    val runs = ArrayList<IconRun>(16)
    var runPackage: String? = null
    var runStart = 0L
    var runEnd = 0L
    for (sample in samples) {
        val packageName = sample.packageName
        if (packageName != runPackage) {
            val finished = runPackage
            if (finished != null) addRun(runs, icons, finished, runStart, runEnd)
            runPackage = packageName
            runStart = sample.timestampMillis
        }
        runEnd = sample.timestampMillis
    }
    runPackage?.let { addRun(runs, icons, it, runStart, runEnd) }
    if (runs.isEmpty()) return

    // Average wattage per app: inside a column the higher icon is the one drawing more power.
    val powerSum = HashMap<String, Double>(8)
    val powerCount = HashMap<String, Int>(8)
    for (sample in samples) {
        val packageName = sample.packageName ?: continue
        powerSum[packageName] = (powerSum[packageName] ?: 0.0) + abs(sample.powerMilliWatts).toDouble()
        powerCount[packageName] = (powerCount[packageName] ?: 0) + 1
    }
    val averageWatts = powerSum.mapValues { (packageName, sum) ->
        (sum / (powerCount[packageName] ?: 1) / 1000.0).toFloat()
    }

    // Column -> packages (Scene's 32 px buckets).
    val columns = LinkedHashMap<Int, MutableList<String>>(32)
    for (run in runs) {
        val xStart = xOffsetForTimestamp(run.startMillis)
        val xEnd = xOffsetForTimestamp(run.endMillis)
        val firstColumn = (xStart - xStart % ColumnPx).toInt()
        val lastColumn = (xEnd - xEnd % ColumnPx).toInt()
        var column = firstColumn
        while (column <= lastColumn) {
            val packages = columns.getOrPut(column) { ArrayList(2) }
            if (!packages.contains(run.packageName)) packages += run.packageName
            column += ColumnPx.toInt()
        }
    }

    for ((columnX, packages) in columns) {
        val x = plotLeft + columnX
        if (x < plotLeft || x + IconPx > plotRight) continue
        // Lowest icon first; icons with a larger average draw end up higher in the stack.
        var y = baselineY - IconPx
        for (packageName in packages.sortedBy { averageWatts[it] ?: 0f }) {
            val bitmap = icons[packageName] ?: continue
            if (y < plotTop) break
            drawImage(
                image = bitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                dstSize = IntSize(IconPx.roundToInt(), IconPx.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
            // Scene outlines every icon with a rounded rect so it stays readable on dark curves.
            drawRoundRect(
                color = outlineColor.copy(alpha = 0.5f),
                topLeft = Offset(x, y),
                size = Size(IconPx, IconPx),
                cornerRadius = CornerRadius(IconCornerPx),
                style = Stroke(width = IconOutlinePx),
            )
            y -= IconStepPx
        }
    }
}

private fun addRun(
    runs: MutableList<IconRun>,
    icons: Map<String, ImageBitmap>,
    packageName: String,
    startMillis: Long,
    endMillis: Long,
) {
    // Scene ignores stretches shorter than 3 s (a single sample) and packages without an icon.
    if (!icons.containsKey(packageName)) return
    if (endMillis - startMillis <= IconMinDurationMillis) return
    runs += IconRun(packageName, startMillis, endMillis)
}

/** One foreground stretch, in wall-clock time. */
private data class IconRun(
    val packageName: String,
    val startMillis: Long,
    val endMillis: Long,
)

/** Axis span and how many ticks Scene would draw inside it. */
private data class AxisSpan(
    val minutes: Int,
    val labelCount: Int,
)

/** The tick plan derived from Scene's `d(double)` + label-count table. */
private data class TickPlan(
    /** Labelled ticks: index in [labels] -> minute value. */
    val labels: List<Pair<Int, Int>>,
    /** Unlabelled vertical grid lines, in minutes. */
    val gridMinutes: List<Int>,
    /** Minutes that get a label, in order (used for measuring the strings). */
    val labelledMinutes: List<Int>,
)

/** Levels that get a label: every 20 %, including the bottom 0 % baseline. */
private val LevelLabelValues = listOf(100, 80, 60, 40, 20, 0)

/** The level axis spans 0 %…100 %; only every other grid line carries a label. */
private const val AxisMinLevel = 0f
private const val AxisMaxLevel = 100f

/** Scene's icon geometry, in raw pixels (the same constants its `PowerStatView` uses). */
private const val ColumnPx = 32f
private const val IconPx = 32f
private const val IconStepPx = 34f
private const val IconCornerPx = 8f
private const val IconOutlinePx = 2f
private const val IconMinDurationMillis = 3_000L

/**
 * Scene's axis span ladder (`PowerStatView.f5449a`) and the matching label counts (`f5451b`):
 * the chart shows the smallest supported span that covers the session; past 24 h it grows in 12 h
 * steps and uses the default 12 labels.
 */
private val SpanLadderMinutes =
    intArrayOf(10, 15, 30, 45, 60, 90, 120, 150, 180, 240, 300, 360, 420, 480, 540, 600, 660, 720, 960, 1200, 1440)
private val SpanLabelCounts =
    intArrayOf(10, 6, 12, 9, 12, 9, 12, 10, 12, 12, 10, 12, 7, 8, 9, 10, 11, 12, 8, 12, 12)

/** Span used while nothing is recorded yet (Scene falls back to 30 minutes). */
private const val EmptySpanMinutes = 30

/**
 * Keeps screen-off markers visible without letting sleep time consume the usage chart's x axis.
 * The first sample after the screen turns off and the first one after it turns back on are still
 * connected, but that transition is represented by one normal sampling step (3 s) rather than
 * the entire wall-clock gap between the two points.
 */
internal fun compressScreenOffGaps(samples: List<ChartSample>): List<ChartSample> {
    if (samples.size < 2) return samples

    val compressed = ArrayList<ChartSample>(samples.size)
    var timestamp = samples.first().timestampMillis
    compressed += samples.first().copy(timestampMillis = timestamp)

    for (index in 1 until samples.size) {
        val previous = samples[index - 1]
        val current = samples[index]
        val elapsed = (current.timestampMillis - previous.timestampMillis).coerceAtLeast(0L)
        val screenOffTransition = previous.style.isScreenOff() || current.style.isScreenOff()
        val displayedElapsed = if (screenOffTransition) {
            elapsed.coerceAtMost(ChartTickMillis)
        } else {
            elapsed
        }
        timestamp += displayedElapsed
        compressed += current.copy(timestampMillis = timestamp)
    }
    return compressed
}

private fun ChartSegmentStyle.isScreenOff(): Boolean = when (this) {
    ChartSegmentStyle.ScreenOff, ChartSegmentStyle.MissedRecordScreenOff -> true

    ChartSegmentStyle.ScreenOn, ChartSegmentStyle.MissedRecordScreenOn -> false
}

private const val ChartTickMillis = 3_000L

internal fun Color.inverted(): Color = Color(
    red = 1f - red,
    green = 1f - green,
    blue = 1f - blue,
    alpha = alpha,
)

/** Picks the axis span for the recorded session, mirroring Scene's `d(double)`. */
private fun axisSpan(samples: List<ChartSample>): AxisSpan {
    val first = samples.firstOrNull()?.timestampMillis ?: return AxisSpan(EmptySpanMinutes, 12)
    val last = samples.lastOrNull()?.timestampMillis ?: return AxisSpan(EmptySpanMinutes, 12)
    val minutes = (last - first) / 60_000.0
    val largest = SpanLadderMinutes.last()
    if (minutes <= largest) {
        for (index in SpanLadderMinutes.indices) {
            if (minutes <= SpanLadderMinutes[index]) {
                return AxisSpan(SpanLadderMinutes[index], SpanLabelCounts[index])
            }
        }
        return AxisSpan(largest, SpanLabelCounts.last())
    }
    var span = largest
    while (span < minutes) span += 720
    return AxisSpan(span, 12)
}

/**
 * Scene draws `labelCount` tick intervals across the span and labels every other one (offset by the
 * parity of `labelCount`), which is why a 45 min axis reads `0 · 5m · 15m · 25m · 35m · 45m`.
 */
private fun tickPlan(spanMinutes: Int, labelCount: Int): TickPlan {
    val interval = spanMinutes.toFloat() / labelCount.coerceAtLeast(1)
    val parity = labelCount % 2
    val labels = ArrayList<Pair<Int, Int>>(labelCount / 2 + 2)
    val gridMinutes = ArrayList<Int>(labelCount + 1)
    for (index in 0..labelCount) {
        val minute = (index * interval).roundToInt()
        gridMinutes += minute
        if (index == 0 || index % 2 == parity) labels += index to minute
    }
    return TickPlan(labels = labels, gridMinutes = gridMinutes, labelledMinutes = labels.map { it.second })
}
