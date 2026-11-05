package dev.shiko.batterymonitor.data

/**
 * Running per-package aggregates for the current session (Scene's `group by package` query, done
 * incrementally instead of over a database).
 *
 * Only screen-on, discharging samples are folded in, so `sampleCount × 3 s` is each app's usage
 * duration and the means match Scene's `avg(current × voltage)` / `avg(temperature)`. Whole-session
 * usage and power are tracked separately and include lower-frequency screen-off samples.
 */
internal data class PackageAggregate(
    val sampleCount: Int = 0,
    /** Σ(current_mA × voltage_V) in mW; negative while discharging. */
    val powerMilliWattSum: Double = 0.0,
    /** Σ current in mA; negative while discharging. */
    val currentMilliAmpSum: Double = 0.0,
    val temperatureSum: Double = 0.0,
    val temperatureMin: Float = Float.MAX_VALUE,
    val temperatureMax: Float = -Float.MAX_VALUE,
) {

    fun add(currentMilliAmps: Float, powerMilliWatts: Float, temperatureCelsius: Float): PackageAggregate =
        copy(
            sampleCount = sampleCount + 1,
            powerMilliWattSum = powerMilliWattSum + powerMilliWatts,
            currentMilliAmpSum = currentMilliAmpSum + currentMilliAmps,
            temperatureSum = temperatureSum + temperatureCelsius,
            temperatureMin = minOf(temperatureMin, temperatureCelsius),
            temperatureMax = maxOf(temperatureMax, temperatureCelsius),
        )

    fun merge(other: PackageAggregate): PackageAggregate = PackageAggregate(
        sampleCount = sampleCount + other.sampleCount,
        powerMilliWattSum = powerMilliWattSum + other.powerMilliWattSum,
        currentMilliAmpSum = currentMilliAmpSum + other.currentMilliAmpSum,
        temperatureSum = temperatureSum + other.temperatureSum,
        temperatureMin = minOf(temperatureMin, other.temperatureMin),
        temperatureMax = maxOf(temperatureMax, other.temperatureMax),
    )

    /** Positive watts, like the UI shows. */
    val averagePowerWatts: Float
        get() = if (sampleCount <= 0) 0f else (-powerMilliWattSum / sampleCount / 1000.0).toFloat()

    /** Positive mA, like Scene's mA mode (`-avgCurrent`). */
    val averageCurrentMilliAmps: Float
        get() = if (sampleCount <= 0) 0f else (-currentMilliAmpSum / sampleCount).toFloat()

    val averageTemperatureCelsius: Float
        get() = if (sampleCount <= 0) 0f else (temperatureSum / sampleCount).toFloat()
}

/** Compact codec for [PackageAggregate], used by [SessionStore]. */
internal object AggregateCodec {

    private const val ENTRY_SEPARATOR = ";"
    private const val FIELD_SEPARATOR = ","

    fun encode(aggregates: Map<String, PackageAggregate>): String =
        aggregates.entries.joinToString(ENTRY_SEPARATOR) { (packageName, aggregate) ->
            listOf(
                packageName,
                aggregate.sampleCount.toString(),
                aggregate.powerMilliWattSum.toLong().toString(),
                aggregate.currentMilliAmpSum.toLong().toString(),
                aggregate.temperatureSum.toLong().toString(),
                (aggregate.temperatureMin * 10f).toInt().toString(),
                (aggregate.temperatureMax * 10f).toInt().toString(),
            ).joinToString(FIELD_SEPARATOR)
        }

    fun decode(encoded: String?): Map<String, PackageAggregate> {
        if (encoded.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, PackageAggregate>()
        for (entry in encoded.split(ENTRY_SEPARATOR)) {
            val parts = entry.split(FIELD_SEPARATOR)
            if (parts.size < 7) continue
            val packageName = parts[0]
            if (packageName.isEmpty()) continue
            val count = parts[1].toIntOrNull() ?: continue
            if (count <= 0) continue
            val aggregate = PackageAggregate(
                sampleCount = count,
                powerMilliWattSum = parts[2].toLongOrNull()?.toDouble() ?: 0.0,
                currentMilliAmpSum = parts[3].toLongOrNull()?.toDouble() ?: 0.0,
                temperatureSum = parts[4].toLongOrNull()?.toDouble() ?: 0.0,
                temperatureMin = (parts[5].toIntOrNull() ?: 0) / 10f,
                temperatureMax = (parts[6].toIntOrNull() ?: 0) / 10f,
            )
            result[packageName] = aggregate
        }
        return result
    }
}
