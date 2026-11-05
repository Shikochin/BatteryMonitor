package dev.shiko.batterymonitor

import dev.shiko.batterymonitor.data.PowerMath
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerMathTest {

    @Test
    fun averageDischargePowerIsWeightedByElapsedTime() {
        // -2 W for 3 s and -1 W for 15 s gives (-6 + -15) W·s / 18 s.
        val weightedMilliWattMillis = -2_000.0 * 3_000 - 1_000.0 * 15_000

        assertEquals(
            1.1667f,
            PowerMath.averageDischargePowerWatts(weightedMilliWattMillis, 18_000L),
            0.001f,
        )
    }

    @Test
    fun averageDischargePowerIsZeroWithoutElapsedSamples() {
        assertEquals(0f, PowerMath.averageDischargePowerWatts(-5_000.0, 0L), 0f)
    }
}
