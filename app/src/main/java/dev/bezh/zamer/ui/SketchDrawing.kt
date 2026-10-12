package dev.bezh.zamer.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bezh.zamer.domain.Formatter
import dev.bezh.zamer.domain.Pt
import dev.bezh.zamer.domain.Rect
import dev.bezh.zamer.domain.SideRef
import dev.bezh.zamer.domain.SketchShape
import kotlin.math.atan2

/** Where a sketch's (0, 0) lands, in pixels, and how many pixels one of its units takes. */
internal data class SketchView(val originX: Float, val originY: Float, val pxPerUnit: Float) {
    fun at(p: Pt) = Offset(originX + p.x * pxPerUnit, originY + p.y * pxPerUnit)
}

/**
 * The view that centres [points] in a [width] by [height] area, [padding] clear of its edges, with the pixels per
 * unit kept in [zoom]; null with no points.
 */
internal fun fitView(
    points: List<Pt>,
    width: Float,
    height: Float,
    padding: Float,
    zoom: ClosedFloatingPointRange<Float> = 0f..Float.MAX_VALUE,
): SketchView? {
    if (points.isEmpty()) return null
    val left = points.minOf { it.x }
    val right = points.maxOf { it.x }
    val top = points.minOf { it.y }
    val bottom = points.maxOf { it.y }
    // A straight line has no extent across; the other direction alone decides the zoom.
    val across = (width - 2 * padding) / (right - left).coerceAtLeast(1)
    val down = (height - 2 * padding) / (bottom - top).coerceAtLeast(1)
    val k = minOf(across, down).coerceIn(zoom)
    return SketchView(width / 2f - (left + right) / 2f * k, height / 2f - (top + bottom) / 2f * k, k)
}

/** The colours a sketch is drawn in. [halo], if any, outlines lines so they stand out on a photo. */
internal data class SketchColors(
    val line: Color,
    val raised: Color,
    val label: Color,
    val onLabel: Color,
    val selected: Color,
    val onSelected: Color,
    val area: Color,
    val onArea: Color,
    val halo: Color? = null,
)

internal fun planColors(scheme: ColorScheme) = SketchColors(
    line = scheme.onSurface,
    raised = scheme.primary,
    label = scheme.surfaceContainerHigh,
    onLabel = scheme.onSurface,
    selected = scheme.primary,
    onSelected = scheme.onPrimary,
    area = scheme.secondaryContainer,
    onArea = scheme.onSecondaryContainer,
)

/** Light lines on dark pills, outlined, so they read on any photo. */
internal fun photoColors(scheme: ColorScheme) = SketchColors(
    line = Color.White,
    raised = scheme.primary,
    label = Color(0xD9201A19),
    onLabel = Color.White,
    selected = scheme.primary,
    onSelected = scheme.onPrimary,
    area = Color(0xF2FFFFFF),
    onArea = Color(0xFF231918),
    halo = Color(0x8C000000),
)

/** How side and area labels are worded and set. */
internal class SketchLabels(val measurer: TextMeasurer, val style: TextStyle, val formatter: Formatter)

/** Side labels shrink no smaller than this to fit a short side. */
private val MinLabelSize = 9.sp

/**
 * Draws [shapes] where [view] puts them, measured sides solid and the rest dashed, with [labels] unless they are null.
 * The [target] side and the [carried] shape are raised and drawn over the others.
 */
internal fun DrawScope.drawShapes(
    shapes: List<SketchShape>,
    view: SketchView,
    colors: SketchColors,
    labels: SketchLabels?,
    target: SideRef? = null,
    carried: Long? = null,
    stroke: Float = 3.dp.toPx(),
) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(stroke * 10 / 3, stroke * 7 / 3))
    val lines = shapes
        .flatMap { s -> s.edges.map { e -> Triple(e, SideRef(s.id, e.side) == target || s.id == carried, s.sides[e.side] != null) } }
        .sortedBy { it.second }
    colors.halo?.let { halo ->
        lines.forEach { (e, raised, _) ->
            val w = if (raised) stroke * 5 / 3 else stroke
            drawLine(halo, view.at(e.a), view.at(e.b), w + 2.dp.toPx(), StrokeCap.Round)
        }
    }
    lines.forEach { (e, raised, measured) ->
        drawLine(
            color = if (raised) colors.raised else colors.line,
            start = view.at(e.a),
            end = view.at(e.b),
            strokeWidth = if (raised) stroke * 5 / 3 else stroke,
            cap = StrokeCap.Round,
            pathEffect = if (measured) null else dash,
        )
    }
    if (labels != null) drawLabels(shapes, view, colors, labels, target)
}

private fun DrawScope.drawLabels(shapes: List<SketchShape>, view: SketchView, colors: SketchColors, labels: SketchLabels, target: SideRef?) {
    shapes.forEach { s ->
        s.sides.forEachIndexed { side, mm ->
            val e = s.edges.first { it.side == side }
            val a = view.at(e.a)
            val b = view.at(e.b)
            val selected = SideRef(s.id, side) == target
            val text = mm?.let { labels.formatter.length(it).toString() } ?: "?"
            // Shrinks the text to fit a short side, and moves it off the side if even the smallest won't fit.
            val room = (b - a).getDistance()
            var style = labels.style.copy(color = if (selected) colors.onSelected else colors.onLabel)
            var layout = labels.measurer.measure(text, style, density = this)
            while (pillWidth(layout) > room && style.fontSize > MinLabelSize) {
                style = style.copy(fontSize = maxOf(style.fontSize.value - 1f, MinLabelSize.value).sp)
                layout = labels.measurer.measure(text, style, density = this)
            }
            pill(
                text = layout,
                center = (a + b) / 2f,
                degrees = upright(Math.toDegrees(atan2(b.y - a.y, b.x - a.x).toDouble()).toFloat()),
                background = if (selected) colors.selected else colors.label,
                beside = pillWidth(layout) > room,
            )
        }
        if (s is Rect) {
            s.area?.let { area ->
                val text = labels.measurer.measure(labels.formatter.area(area).toString(), labels.style.copy(color = colors.onArea), density = this)
                pill(text, (view.at(s.a) + view.at(s.b)) / 2f, 0f, colors.area)
            }
        }
    }
}

/** Turns a line's angle so text along it never reads upside down; vertical text reads bottom to top. */
private fun upright(degrees: Float) = when {
    degrees >= 90f -> degrees - 180f
    degrees < -90f -> degrees + 180f
    else -> degrees
}

private fun DrawScope.pillWidth(text: TextLayoutResult) = text.size.width + 12.dp.toPx()

/**
 * [text] on a rounded pill centred on [center] and turned [degrees]; [beside] lifts it clear of the line under it,
 * which is outward for a rectangle's top and left sides.
 */
internal fun DrawScope.pill(text: TextLayoutResult, center: Offset, degrees: Float, background: Color, beside: Boolean = false) {
    val w = pillWidth(text)
    val h = text.size.height + 4.dp.toPx()
    val c = if (beside) center - Offset(0f, h / 2 + 5.dp.toPx()) else center
    rotate(degrees, pivot = center) {
        drawRoundRect(background, Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(h / 2))
        drawText(text, topLeft = Offset(c.x - text.size.width / 2f, c.y - text.size.height / 2f))
    }
}
