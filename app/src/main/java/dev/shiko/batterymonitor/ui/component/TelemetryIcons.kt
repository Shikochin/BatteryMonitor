package dev.shiko.batterymonitor.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The four little glyphs of the "使用过程" telemetry row (energy / temperature / voltage /
 * charging state).
 *
 * Miuix ships no battery, thermometer, bolt or plug icon, so these are hand-built
 * [ImageVector]s. They are drawn as *strokes* in a 24dp viewport with a monochrome paint, which is
 * what lets [top.yukonga.miuix.kmp.basic.Icon] tint them with a single colour via a
 * `ColorFilter`; the literal colour below is therefore irrelevant.
 *
 * Everything is kept to a 1.7dp-ish stroke weight and ~20dp of ink so the shapes still read at the
 * 18dp size the card renders them at.
 */

/** Stroke weight used by all four icons, in viewport units. */
private const val StrokeWeight = 1.7f

private fun telemetryIcon(
    name: String,
    block: ImageVector.Builder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply(block).build()

/** Adds one monochrome, round-capped stroke path to the vector under construction. */
private fun ImageVector.Builder.strokePath(block: PathBuilder.() -> Unit) {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = StrokeWeight,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}

/** Emits a rounded rectangle outline (corner radius [radius]) as part of the current path. */
private fun PathBuilder.roundedRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float,
) {
    moveTo(left + radius, top)
    lineTo(right - radius, top)
    quadTo(right, top, right, top + radius)
    lineTo(right, bottom - radius)
    quadTo(right, bottom, right - radius, bottom)
    lineTo(left + radius, bottom)
    quadTo(left, bottom, left, bottom - radius)
    lineTo(left, top + radius)
    quadTo(left, top, left + radius, top)
    close()
}

/**
 * Battery cell: an upright rounded body with a terminal nub on top and three "charge" bars inside.
 * Used for `29.0Wh`.
 */
val TelemetryBattery: ImageVector = telemetryIcon("TelemetryBattery") {
    strokePath {
        // Terminal nub.
        moveTo(10.2f, 3.2f)
        lineTo(13.8f, 3.2f)
        // Cell body.
        roundedRect(left = 7.0f, top = 5.6f, right = 17.0f, bottom = 20.8f, radius = 2.4f)
        // Charge bars.
        moveTo(10.0f, 10.4f)
        lineTo(14.0f, 10.4f)
        moveTo(10.0f, 13.4f)
        lineTo(14.0f, 13.4f)
        moveTo(10.0f, 16.4f)
        lineTo(14.0f, 16.4f)
    }
}

/**
 * Flame: used for the battery temperature, `28.5°C`.
 *
 * The silhouette is a classic fire outline — a leaning tip, a bulge on the right and the little
 * "curl" on the lower left that keeps it from reading as a water drop.
 */
val TelemetryFlame: ImageVector = telemetryIcon("TelemetryFlame") {
    strokePath {
        moveTo(8.5f, 14.5f)
        // Lower-left curl (the arc that turns the body back into the flame).
        arcTo(
            horizontalEllipseRadius = 2.5f,
            verticalEllipseRadius = 2.5f,
            theta = 0f,
            isMoreThanHalf = false,
            isPositiveArc = false,
            x1 = 11.0f,
            y1 = 12.0f,
        )
        curveTo(11.0f, 10.6f, 10.5f, 10.0f, 10.0f, 9.0f)
        // The tip, leaning to the left.
        curveTo(8.9f, 6.9f, 9.8f, 4.9f, 12.0f, 3.0f)
        curveTo(12.5f, 5.5f, 14.0f, 7.9f, 16.0f, 9.5f)
        curveTo(18.0f, 11.1f, 19.0f, 13.0f, 19.0f, 15.0f)
        // Bulging body.
        arcTo(
            horizontalEllipseRadius = 7.0f,
            verticalEllipseRadius = 7.0f,
            theta = 0f,
            isMoreThanHalf = true,
            isPositiveArc = true,
            x1 = 5.0f,
            y1 = 15.0f,
        )
        curveTo(5.0f, 13.8f, 5.4f, 12.7f, 6.0f, 12.0f)
        // Close the curl.
        arcTo(
            horizontalEllipseRadius = 2.5f,
            verticalEllipseRadius = 2.5f,
            theta = 0f,
            isMoreThanHalf = false,
            isPositiveArc = false,
            x1 = 8.5f,
            y1 = 14.5f,
        )
        close()
    }
}

/**
 * Lightning bolt inside a circle: used for the battery voltage, `4.402v`.
 */
val TelemetryBolt: ImageVector = telemetryIcon("TelemetryBolt") {
    strokePath {
        // Circle (two half arcs).
        moveTo(12.0f, 2.6f)
        arcTo(
            horizontalEllipseRadius = 9.4f,
            verticalEllipseRadius = 9.4f,
            theta = 0f,
            isMoreThanHalf = true,
            isPositiveArc = true,
            x1 = 12.0f,
            y1 = 21.4f,
        )
        arcTo(
            horizontalEllipseRadius = 9.4f,
            verticalEllipseRadius = 9.4f,
            theta = 0f,
            isMoreThanHalf = true,
            isPositiveArc = true,
            x1 = 12.0f,
            y1 = 2.6f,
        )
        close()
        // Bolt.
        moveTo(13.4f, 6.6f)
        lineTo(9.4f, 13.0f)
        lineTo(12.2f, 13.0f)
        lineTo(10.6f, 17.4f)
    }
}

/**
 * Unplugged plug: two prongs, a rounded body and a loose cable. Used for the charging state,
 * `未充电`.
 */
val TelemetryPlug: ImageVector = telemetryIcon("TelemetryPlug") {
    strokePath {
        // Prongs.
        moveTo(9.5f, 3.0f)
        lineTo(9.5f, 7.2f)
        moveTo(14.5f, 3.0f)
        lineTo(14.5f, 7.2f)
        // Body.
        roundedRect(left = 6.8f, top = 7.2f, right = 17.2f, bottom = 13.4f, radius = 2.2f)
        // Cable.
        moveTo(12.0f, 13.4f)
        lineTo(12.0f, 16.2f)
        curveTo(12.0f, 18.6f, 13.4f, 20.4f, 15.8f, 20.6f)
    }
}
