package dev.bezh.zamer.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as Bounds
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bezh.zamer.domain.Formatter
import dev.bezh.zamer.domain.Note
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
 * The view that centres [points], and [boxes] that keep their size in pixels around their spots, in a [width] by
 * [height] area, [padding] clear of its edges, with the pixels per unit kept in [zoom]; null with nothing to show.
 */
internal fun fitView(
    points: List<Pt>,
    width: Float,
    height: Float,
    padding: Float,
    zoom: ClosedFloatingPointRange<Float> = 0f..Float.MAX_VALUE,
    boxes: List<Pair<Pt, Size>> = emptyList(),
): SketchView? {
    val k = fitZoom(points, boxes, width - 2 * padding, height - 2 * padding, zoom) ?: return null
    val reach = reach(points, boxes, k)
    return SketchView(width / 2f - reach.center.x, height / 2f - reach.center.y, k)
}

/**
 * The most pixels per unit, within [zoom], at which [points] and [boxes] span no more than [width] by [height]; null
 * with nothing to show.
 */
internal fun fitZoom(points: List<Pt>, boxes: List<Pair<Pt, Size>>, width: Float, height: Float, zoom: ClosedFloatingPointRange<Float>): Float? {
    val spots = points + boxes.map { it.first }
    if (spots.isEmpty()) return null
    // A straight line has no extent across; the other direction alone decides the zoom.
    val across = width / (spots.maxOf { it.x } - spots.minOf { it.x }).coerceAtLeast(1)
    val down = height / (spots.maxOf { it.y } - spots.minOf { it.y }).coerceAtLeast(1)
    var most = minOf(across, down).coerceIn(zoom)
    fun fits(k: Float) = reach(points, boxes, k).let { it.width <= width && it.height <= height }
    if (fits(most)) return most
    // Boxes keep their size as the drawing shrinks, so search for the zoom that leaves them room.
    var least = zoom.start
    if (!fits(least)) return least
    repeat(32) {
        val k = (least + most) / 2
        if (fits(k)) least = k else most = k
    }
    return least
}

/** How far [points] and [boxes] reach, in pixels from the sketch's (0, 0), at [k] pixels per unit. */
internal fun reach(points: List<Pt>, boxes: List<Pair<Pt, Size>>, k: Float): Bounds {
    val all = points.map { Bounds(Offset(it.x * k, it.y * k), Size.Zero) } +
        boxes.map { (p, size) -> Bounds(Offset(p.x * k - size.width / 2, p.y * k - size.height / 2), size) }
    return all.reduce { a, b -> Bounds(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
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

/** How side and area labels and notes are worded and set. */
internal class SketchLabels(val measurer: TextMeasurer, val style: TextStyle, val formatter: Formatter)

/** Side labels shrink no smaller than this to fit a short side. */
private val MinLabelSize = 9.sp

/**
 * Draws [shapes] where [view] puts them, measured sides solid and the rest dashed, with [labels] and notes unless
 * [labels] is null. The [target] side, the [picked] note and the [carried] shape are raised and drawn over the others.
 */
internal fun DrawScope.drawShapes(
    shapes: List<SketchShape>,
    view: SketchView,
    colors: SketchColors,
    labels: SketchLabels?,
    target: SideRef? = null,
    picked: Long? = null,
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
    if (labels != null) {
        drawLabels(shapes, view, colors, labels, target)
        drawNotes(shapes.filterIsInstance<Note>(), view, colors, labels, raised = setOfNotNull(picked, carried))
    }
}

private fun DrawScope.drawNotes(notes: List<Note>, view: SketchView, colors: SketchColors, labels: SketchLabels, raised: Set<Long>) {
    notes.sortedBy { it.id in raised }.forEach { note ->
        val up = note.id in raised
        val text = noteText(note, labels, if (up) colors.onSelected else colors.onLabel)
        val box = noteBox(note, view, text)
        drawRoundRect(if (up) colors.selected else colors.label, box.topLeft, box.size, CornerRadius(NoteCorner.toPx()))
        drawText(text, topLeft = Offset(box.center.x - text.size.width / 2f, box.center.y - text.size.height / 2f))
    }
}

/** A note wraps at this width, whatever the zoom, so a long one stays a block rather than a strip. */
private val NoteWidth = 200.dp
private const val NOTE_LINES = 3
private val NoteCorner = 8.dp

/** [note]'s text laid out as it is drawn: the same size on screen at any zoom. */
internal fun Density.noteText(note: Note, labels: SketchLabels, color: Color = Color.Unspecified): TextLayoutResult =
    labels.measurer.measure(
        note.text,
        labels.style.copy(color = color, textAlign = TextAlign.Center),
        overflow = TextOverflow.Ellipsis,
        maxLines = NOTE_LINES,
        constraints = Constraints(maxWidth = NoteWidth.roundToPx()),
        density = this,
    )

/** The box [text] is drawn on, for a note's spot to sit in its middle. */
internal fun Density.noteSize(text: TextLayoutResult) = Size(text.size.width + 16.dp.toPx(), text.size.height + 8.dp.toPx())

/** Where [note], laid out as [text], is drawn in [view]; also where a finger finds it. */
internal fun Density.noteBox(note: Note, view: SketchView, text: TextLayoutResult): Bounds {
    val size = noteSize(text)
    return Bounds(view.at(note.at) - Offset(size.width / 2, size.height / 2), size)
}

/** Each note's spot and the size of its box, for framing a sketch with its notes whole. */
internal fun Density.noteBoxes(shapes: List<SketchShape>, labels: SketchLabels): List<Pair<Pt, Size>> =
    shapes.filterIsInstance<Note>().map { it.at to noteSize(noteText(it, labels)) }

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
