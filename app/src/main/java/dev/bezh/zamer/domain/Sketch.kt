package dev.bezh.zamer.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A dot of the sketch grid; one unit is the spacing between dots. */
@Serializable
data class Pt(val x: Int, val y: Int)

/** A line a shape is drawn with, standing for the shape's side number [side]. */
data class Edge(val a: Pt, val b: Pt, val side: Int)

@Serializable
sealed interface SketchShape {
    val id: Long

    /** Each side's measured length in millimetres, null until it is measured. */
    val sides: List<Int?>

    val edges: List<Edge>

    fun measured(side: Int, mm: Int): SketchShape
}

@Serializable
@SerialName("segment")
data class Segment(override val id: Long, val a: Pt, val b: Pt, val mm: Int? = null) : SketchShape {
    override val sides get() = listOf(mm)
    override val edges get() = listOf(Edge(a, b, 0))
    override fun measured(side: Int, mm: Int) = copy(mm = mm)
}

/** An axis-aligned rectangle between opposite corners [a] and [b]; side 0 is the width, side 1 the height. */
@Serializable
@SerialName("rect")
data class Rect(
    override val id: Long,
    val a: Pt,
    val b: Pt,
    val widthMm: Int? = null,
    val heightMm: Int? = null,
) : SketchShape {
    override val sides get() = listOf(widthMm, heightMm)

    /** Top, bottom, left, right, so the first edge of each side is where its label goes. */
    override val edges: List<Edge>
        get() {
            val l = minOf(a.x, b.x)
            val r = maxOf(a.x, b.x)
            val t = minOf(a.y, b.y)
            val btm = maxOf(a.y, b.y)
            return listOf(
                Edge(Pt(l, t), Pt(r, t), 0),
                Edge(Pt(l, btm), Pt(r, btm), 0),
                Edge(Pt(l, t), Pt(l, btm), 1),
                Edge(Pt(r, t), Pt(r, btm), 1),
            )
        }

    /** Square metres, once both sides are measured. */
    val area: Double?
        get() = if (widthMm != null && heightMm != null) widthMm / 1000.0 * (heightMm / 1000.0) else null

    override fun measured(side: Int, mm: Int) = if (side == 0) copy(widthMm = mm) else copy(heightMm = mm)
}

/** Side [side] of the shape [shapeId]. */
@Serializable
data class SideRef(val shapeId: Long, val side: Int)

/** Shapes drawn on the grid; [target] is the side the next reading measures. */
@Serializable
data class Sketch(val shapes: List<SketchShape> = emptyList(), val target: SideRef? = null) {
    init {
        require(target == null || isSide(target)) { "the target is a side of a shape in the sketch" }
    }

    /** The new shape's first side waits for a reading. A shape with no length or no area is ignored. */
    fun add(shape: SketchShape): Sketch =
        if (shape.edges.any { it.a == it.b } || shapes.any { it.id == shape.id }) this
        else Sketch(shapes + shape, SideRef(shape.id, 0))

    /** Measures the target with [mm], then waits for the same shape's next unmeasured side, if any. */
    fun put(mm: Int): Sketch {
        val t = target ?: return this
        val shape = shapes.first { it.id == t.shapeId }.measured(t.side, mm)
        val next = shape.sides.indexOfFirst { it == null }.takeIf { it >= 0 }?.let { SideRef(shape.id, it) }
        return Sketch(shapes.map { if (it.id == shape.id) shape else it }, next)
    }

    /** The next reading re-measures [ref]. */
    fun select(ref: SideRef) = if (isSide(ref)) copy(target = ref) else this

    fun delete(id: Long) = Sketch(shapes.filterNot { it.id == id }, target?.takeIf { it.shapeId != id })

    /** Removes the shape drawn last. */
    fun undo() = shapes.lastOrNull()?.let { delete(it.id) } ?: this

    fun clear() = Sketch()

    /** Square metres of every fully measured rectangle; null when there is none. */
    val totalArea: Double?
        get() = shapes.mapNotNull { (it as? Rect)?.area }.takeIf { it.isNotEmpty() }?.sum()

    /** The dot nearest to grid position ([x], [y]), preferring a shape's end or corner within [reach] units. */
    fun snap(x: Float, y: Float, reach: Float = 1f): Pt {
        val corner = shapes.asSequence()
            .flatMap { s -> s.edges.flatMap { listOf(it.a, it.b) } }
            .map { it to hypot(it.x - x, it.y - y) }
            .filter { it.second <= reach }
            .minByOrNull { it.second }
        return corner?.first ?: Pt(x.roundToInt(), y.roundToInt())
    }

    /** The side drawn nearest to grid position ([x], [y]), if one is within [tolerance] units. */
    fun sideAt(x: Float, y: Float, tolerance: Float): SideRef? = shapes
        .flatMap { s -> s.edges.map { SideRef(s.id, it.side) to distance(x, y, it) } }
        .filter { it.second <= tolerance }
        .minByOrNull { it.second }
        ?.first

    private fun isSide(ref: SideRef) = shapes.any { it.id == ref.shapeId && ref.side in it.sides.indices }

    private fun distance(x: Float, y: Float, e: Edge): Float {
        val dx = (e.b.x - e.a.x).toFloat()
        val dy = (e.b.y - e.a.y).toFloat()
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((x - e.a.x) * dx + (y - e.a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(x - (e.a.x + t * dx), y - (e.a.y + t * dy))
    }
}
