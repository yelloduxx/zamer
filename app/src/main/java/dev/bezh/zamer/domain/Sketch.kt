package dev.bezh.zamer.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

/** Millimetres between neighbouring dots of the sketch grid, so the grid is a scale reference. */
const val GRID_MM = 500

/** A point of the sketch, in millimetres. */
@Serializable
data class Pt(val x: Int, val y: Int) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
}

/** A line a shape is drawn with, standing for the shape's side number [side]. */
data class Edge(val a: Pt, val b: Pt, val side: Int)

@Serializable
sealed interface SketchShape {
    val id: Long

    /** Each side's measured length in millimetres, null until it is measured. */
    val sides: List<Int?>

    val edges: List<Edge>

    /** Every end or corner, where other shapes can join this one. */
    val points: List<Pt>

    /** The same shape with every point passed through [f]. */
    fun mapPoints(f: (Pt) -> Pt): SketchShape

    /** The shape with side [side] measured as [mm] and redrawn that long, its first point staying where it is. */
    fun measured(side: Int, mm: Int): SketchShape

    /** The shape with side [side] labelled [mm], drawn as it was. */
    fun labelled(side: Int, mm: Int): SketchShape
}

@Serializable
@SerialName("segment")
data class Segment(override val id: Long, val a: Pt, val b: Pt, val mm: Int? = null) : SketchShape {
    override val sides get() = listOf(mm)
    override val edges get() = listOf(Edge(a, b, 0))
    override val points get() = listOf(a, b)
    override fun mapPoints(f: (Pt) -> Pt) = copy(a = f(a), b = f(b))
    override fun labelled(side: Int, mm: Int) = copy(mm = mm)

    /** Moves [b] along the line, so the direction stays. */
    override fun measured(side: Int, mm: Int): Segment {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        val length = hypot(dx, dy)
        // A wall stretched until its ends met has no direction left to keep.
        if (length == 0.0) return copy(mm = mm)
        val k = mm / length
        return copy(b = Pt(a.x + (dx * k).roundToInt(), a.y + (dy * k).roundToInt()), mm = mm)
    }
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

    /** [a] and [b] first, then the other two corners. */
    override val points get() = listOf(a, b, Pt(a.x, b.y), Pt(b.x, a.y))
    override fun mapPoints(f: (Pt) -> Pt) = copy(a = f(a), b = f(b))

    /** Square metres, once both sides are measured. */
    val area: Double?
        get() = if (widthMm != null && heightMm != null) widthMm / 1000.0 * (heightMm / 1000.0) else null

    override fun labelled(side: Int, mm: Int) = if (side == 0) copy(widthMm = mm) else copy(heightMm = mm)

    /** Moves [b] away from or towards [a], on the same side of it. */
    override fun measured(side: Int, mm: Int) =
        if (side == 0) copy(b = Pt(a.x + mm * (b.x - a.x).sign, b.y), widthMm = mm)
        else copy(b = Pt(b.x, a.y + mm * (b.y - a.y).sign), heightMm = mm)
}

/** A horizontal or, if [vertical], vertical line through [through] that a drawing finger lined up with. */
data class Guide(val through: Pt, val vertical: Boolean)

/** Where a drawing finger lands, and the [guides] it lined up along to get there. */
data class Snap(val at: Pt, val guides: List<Guide> = emptyList())

/** Side [side] of the shape [shapeId]. */
@Serializable
data class SideRef(val shapeId: Long, val side: Int)

/** How many earlier states of the shapes [Sketch.undo] can step back through. */
private const val UNDO_DEPTH = 30

/**
 * Shapes drawn to scale; [target] is the side the next reading measures. [past] holds the shapes as they were before
 * each recent change, oldest first, so a draw, a reading, a move or a deletion can be undone.
 */
@Serializable
data class Sketch(
    val shapes: List<SketchShape> = emptyList(),
    val target: SideRef? = null,
    val past: List<List<SketchShape>> = emptyList(),
) {
    init {
        require(target == null || shapes.has(target)) { "the target is a side of a shape in the sketch" }
    }

    /** The new shape's first side waits for a reading. A shape with no length or no area is ignored. */
    fun add(shape: SketchShape): Sketch =
        if (shape.edges.any { it.a == it.b } || shapes.any { it.id == shape.id }) this
        else changed(shapes + shape, SideRef(shape.id, 0))

    /**
     * Measures the target with [mm] and, [toScale], redraws it that long with the shapes joined to it following; then
     * waits for the same shape's next unmeasured side, if any.
     */
    fun put(mm: Int, toScale: Boolean = true): Sketch {
        val t = target ?: return this
        val before = shapes.first { it.id == t.shapeId }
        val shape = if (toScale) before.measured(t.side, mm) else before.labelled(t.side, mm)
        val next = shape.sides.indexOfFirst { it == null }.takeIf { it >= 0 }?.let { SideRef(shape.id, it) }
        return changed(follow(before, shape), next)
    }

    /** The next reading re-measures [ref]. */
    fun select(ref: SideRef) = if (shapes.has(ref)) copy(target = ref) else this

    fun delete(id: Long) = changed(shapes.filterNot { it.id == id }, target?.takeIf { it.shapeId != id })

    /** Shifts shape [id] by [d] alone; shapes joined to it stay where they are and its measurements are kept. */
    fun move(id: Long, d: Pt) = changed(shapes.map { if (it.id == id) it.mapPoints { p -> p + d } else it }, target)

    /**
     * Steps back over the last change to the shapes. With nothing remembered, as in a sketch saved before changes
     * were, it removes the shape drawn last.
     */
    fun undo(): Sketch {
        val previous = past.lastOrNull() ?: shapes.dropLast(1)
        return Sketch(previous, target?.takeIf { previous.has(it) }, past.dropLast(1))
    }

    fun clear() = Sketch()

    /** [next] in place of the shapes, remembering these for [undo] unless nothing changed. */
    private fun changed(next: List<SketchShape>, target: SideRef?) =
        if (next == shapes) copy(target = target) else Sketch(next, target, (past + listOf(shapes)).takeLast(UNDO_DEPTH))

    /** Square metres of every fully measured rectangle; null when there is none. */
    val totalArea: Double?
        get() = shapes.mapNotNull { (it as? Rect)?.area }.takeIf { it.isNotEmpty() }?.sum()

    /**
     * Where a finger at ([x], [y]) draws from or to, taking the first that applies: a shape's end or corner within
     * [reach]; the x or y of [from], the stroke's start, within [align], so the line comes out exactly straight; the x
     * or y of any shape's point within [align]; a point on a side within [reach], so a wall can butt into another; the
     * nearest dot of a grid [step] apart, or the finger itself with no grid. Lining up one axis leaves the other to the
     * rules after it.
     */
    fun snap(x: Float, y: Float, reach: Float, align: Float, step: Int?, from: Pt? = null): Snap {
        val points = shapes.flatMap { it.points }
        points.map { it to hypot(it.x - x, it.y - y) }.filter { it.second <= reach }.minByOrNull { it.second }
            ?.let { return Snap(it.first) }

        fun lineUp(value: Float, vertical: Boolean): Guide? {
            fun gap(p: Pt) = abs((if (vertical) p.x else p.y) - value)
            val start = from?.takeIf { gap(it) <= align }
            return (start ?: points.filter { gap(it) <= align }.minByOrNull(::gap))?.let { Guide(it, vertical) }
        }
        val gx = lineUp(x, vertical = true)
        val gy = lineUp(y, vertical = false)
        val guides = listOfNotNull(gx, gy)
        val lx = gx?.through?.x
        val ly = gy?.through?.y
        if (lx != null && ly != null) return Snap(Pt(lx, ly), guides)

        val junction = shapes.asSequence()
            .flatMap { it.edges }
            .mapNotNull { e ->
                when {
                    lx != null -> e.crossing(lx.toFloat(), vertical = true)
                    ly != null -> e.crossing(ly.toFloat(), vertical = false)
                    else -> e.nearest(x, y)
                }
            }
            .map { it to hypot(it.first - x, it.second - y) }
            .filter { it.second <= reach }
            .minByOrNull { it.second }
            ?.first
        if (junction != null) {
            return Snap(Pt(lx ?: junction.first.roundToInt(), ly ?: junction.second.roundToInt()), guides)
        }
        fun dot(v: Float) = if (step == null) v.roundToInt() else (v / step).roundToInt() * step
        return Snap(Pt(lx ?: dot(x), ly ?: dot(y)), guides)
    }

    /**
     * The shift that drops shape [id], dragged by ([dx], [dy]) mm, with one of its points on another shape's end or
     * corner within [reach] mm, so the two join; or else on the nearest dot of a grid [step] mm apart, or just where
     * the finger left it with no grid.
     */
    fun dropShift(id: Long, dx: Float, dy: Float, reach: Float, step: Int? = GRID_MM): Pt {
        val moved = shapes.firstOrNull { it.id == id } ?: return Pt(0, 0)
        val others = shapes.filter { it.id != id }.flatMap { it.points }
        fun gap(from: Pt, to: Pt) = hypot(to.x - (from.x + dx), to.y - (from.y + dy))
        val join = moved.points
            .flatMap { p -> others.map { p to it } }
            .filter { (p, q) -> gap(p, q) <= reach }
            .minByOrNull { (p, q) -> gap(p, q) }
        val (from, to) = join ?: run {
            val s = step ?: return Pt(dx.roundToInt(), dy.roundToInt())
            moved.points
                .map { p -> p to Pt(((p.x + dx) / s).roundToInt() * s, ((p.y + dy) / s).roundToInt() * s) }
                .minBy { (p, q) -> gap(p, q) }
        }
        return to - from
    }

    /** The shape at ([x], [y]) mm: one with a side within [tolerance] mm, or else a rectangle around it. */
    fun shapeAt(x: Float, y: Float, tolerance: Float): Long? =
        sideAt(x, y, tolerance)?.shapeId ?: shapes.lastOrNull {
            it is Rect && x in minOf(it.a.x, it.b.x).toFloat()..maxOf(it.a.x, it.b.x).toFloat() &&
                y in minOf(it.a.y, it.b.y).toFloat()..maxOf(it.a.y, it.b.y).toFloat()
        }?.id

    /** The side drawn nearest to ([x], [y]) mm, if one is within [tolerance] mm. */
    fun sideAt(x: Float, y: Float, tolerance: Float): SideRef? = shapes
        .flatMap { s -> s.edges.map { SideRef(s.id, it.side) to distance(x, y, it) } }
        .filter { it.second <= tolerance }
        .minByOrNull { it.second }
        ?.first

    /**
     * The shapes with [before] replaced by [after] and those joined where its points moved following them.
     * A follower touching nothing that stays slides along whole, so a chain of walls keeps its shape and pulls the
     * next one in turn. One that also touches a point that stays is stretched instead, only its ends at a moving point
     * following, which lets the wall closing a loop give; a rectangle is left as it is rather than skewed.
     * Shapes joined only where [before] stays keep their place and hold their other points too.
     */
    private fun follow(before: SketchShape, after: SketchShape): List<SketchShape> {
        val moves = before.points.zip(after.points).filter { (p, q) -> p != q }
        val done = hashMapOf(after.id to after)
        if (moves.isNotEmpty()) {
            val d = moves.first().let { (p, q) -> q - p }
            val moving = moves.mapTo(HashSet()) { it.first }
            val anchors = before.points.toSet() - moving
            val pinned = anchors.toHashSet()
            for (s in shapes) {
                if (s.id !in done && s.points.any { it in anchors } && s.points.none { it in moving }) {
                    done[s.id] = s
                    pinned += s.points
                }
            }
            val queue = ArrayDeque(moving)
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                for (s in shapes) {
                    if (s.id in done || p !in s.points) continue
                    done[s.id] = if (s.points.none { it in pinned }) {
                        for (q in s.points) if (moving.add(q)) queue.addLast(q)
                        s.mapPoints { it + d }
                    } else {
                        pinned += s.points.filterNot { it in moving }
                        if (s is Rect) s else s.mapPoints { if (it in moving) it + d else it }
                    }
                }
            }
        }
        return shapes.map { done[it.id] ?: it }
    }

    private fun distance(x: Float, y: Float, e: Edge): Float = e.nearest(x, y).let { (px, py) -> hypot(x - px, y - py) }
}

/** The point of this edge nearest to ([x], [y]). */
private fun Edge.nearest(x: Float, y: Float): Pair<Float, Float> {
    val dx = (b.x - a.x).toFloat()
    val dy = (b.y - a.y).toFloat()
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0f, 1f)
    return a.x + t * dx to a.y + t * dy
}

/** Where this edge crosses the line x = [at], or y = [at] unless [vertical]; null if it misses or runs along it. */
private fun Edge.crossing(at: Float, vertical: Boolean): Pair<Float, Float>? {
    val (u0, u1) = if (vertical) a.x to b.x else a.y to b.y
    if (u0 == u1 || at < minOf(u0, u1) || at > maxOf(u0, u1)) return null
    val t = (at - u0) / (u1 - u0)
    return if (vertical) at to a.y + t * (b.y - a.y) else a.x + t * (b.x - a.x) to at
}

private fun List<SketchShape>.has(ref: SideRef) = any { it.id == ref.shapeId && ref.side in it.sides.indices }
