package dev.shiko.batterymonitor

import dev.shiko.batterymonitor.model.ChartSample
import dev.shiko.batterymonitor.model.ChartSegmentStyle
import dev.shiko.batterymonitor.ui.component.compressScreenOffGaps
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageChartTest {

    @Test
    fun compressesScreenOffIntervalToSamplingSteps() {
        val samples = listOf(
            sample(0L, ChartSegmentStyle.ScreenOn),
            sample(15_000L, ChartSegmentStyle.ScreenOff),
            sample(10 * 60_000L, ChartSegmentStyle.ScreenOn),
        )

        val compressed = compressScreenOffGaps(samples)

        assertEquals(listOf(0L, 3_000L, 6_000L), compressed.map { it.timestampMillis })
    }

    @Test
    fun preservesIntervalsWhileScreenStaysOn() {
        val samples = listOf(
            sample(0L, ChartSegmentStyle.ScreenOn),
            sample(3_000L, ChartSegmentStyle.ScreenOn),
            sample(18_000L, ChartSegmentStyle.MissedRecordScreenOn),
        )

        val compressed = compressScreenOffGaps(samples)

        assertEquals(listOf(0L, 3_000L, 18_000L), compressed.map { it.timestampMillis })
    }

    private fun sample(timestampMillis: Long, style: ChartSegmentStyle) = ChartSample(
        timestampMillis = timestampMillis,
        levelPercent = 80,
        style = style,
    )
}
