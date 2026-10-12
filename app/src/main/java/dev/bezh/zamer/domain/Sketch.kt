package dev.bezh.zamer.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

const val GRID_MM = 500

@Serializable
data class Pt(val x: Int, val y: Int) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
}

data class Edge(val a: Pt, val b: Pt, val side: Int)

@Serializable
sealed interface SketchShape {
    val id: Long

    val sides: List<Int?>

    val edges: List<Edge>

    val points: List<Pt>

    val extent: List<Pt> get() = points

    fun mapPoints(f: (Pt) -> Pt): SketchShape

    fun measured(side: Int, mm: Int): SketchShape

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

    override fun measured(side: Int, mm: Int): Segment {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        val length = hypot(dx, dy)
        if (length == 0.0) return copy(mm = mm)
        val k = mm / length
        return copy(b = Pt(a.x + (dx * k).roundToInt(), a.y + (dy * k).roundToInt()), mm = mm)
    }
}

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

    override val points get() = listOf(a, b, Pt(a.x, b.y), Pt(b.x, a.y))
    override fun mapPoints(f: (Pt) -> Pt) = copy(a = f(a), b = f(b))

    val area: Double?
        get() = if (widthMm != null && heightMm != null) widthMm / 1000.0 * (heightMm / 1000.0) else null

    override fun labelled(side: Int, mm: Int) = if (side == 0) copy(widthMm = mm) else copy(heightMm = mm)

    override fun measured(side: Int, mm: Int) =
        if (side == 0) copy(b = Pt(a.x + mm * (b.x - a.x).sign, b.y), widthMm = mm)
        else copy(b = Pt(b.x, a.y + mm * (b.y - a.y).sign), heightMm = mm)
}

@Serializable
@SerialName("note")
data class Note(override val id: Long, val at: Pt, val text: String) : SketchShape {
    override val sides get() = emptyList<Int?>()
    override val edges get() = emptyList<Edge>()
    override val points get() = emptyList<Pt>()
    override val extent get() = listOf(at)
    override fun mapPoints(f: (Pt) -> Pt) = copy(at = f(at))
    override fun measured(side: Int, mm: Int) = this
    override fun labelled(side: Int, mm: Int) = this
}

data class Guide(val through: Pt, val vertical: Boolean)

data class Snap(val at: Pt, val guides: List<Guide> = emptyList())

@Serializable
data class SideRef(val shapeId: Long, val side: Int)

private const val UNDO_DEPTH = 30

@Serializable
data class Sketch(
    val shapes: List<SketchShape> = emptyList(),
    val target: SideRef? = null,
    val past: List<List<SketchShape>> = emptyList(),
) {
    init {
        require(target == null || shapes.has(target)) { "the target is a side of a shape in the sketch" }
    }

    fun add(shape: SketchShape): Sketch =
        if (shape.edges.any { it.a == it.b } || (shape is Note && shape.text.isBlank()) || shapes.any { it.id == shape.id }) this
        else changed(shapes + shape, if (shape.sides.isEmpty()) target else SideRef(shape.id, 0))

    fun write(id: Long, text: String): Sketch =
        if (text.isBlank()) this
        else changed(shapes.map { if (it.id == id && it is Note) it.copy(text = text.trim()) else it }, target)

    fun put(mm: Int, toScale: Boolean = true): Sketch {
        val t = target ?: return this
        val before = shapes.first { it.id == t.shapeId }
        val shape = if (toScale) before.measured(t.side, mm) else before.labelled(t.side, mm)
        val next = shape.sides.indexOfFirst { it == null }.takeIf { it >= 0 }?.let { SideRef(shape.id, it) }
        return changed(follow(before, shape), next)
    }

    fun select(ref: SideRef) = if (shapes.has(ref)) copy(target = ref) else this

    fun delete(id: Long) = changed(shapes.filterNot { it.id == id }, target?.takeIf { it.shapeId != id })

    fun move(id: Long, d: Pt) = changed(shapes.map { if (it.id == id) it.mapPoints { p -> p + d } else it }, target)

    fun undo(): Sketch {
        val previous = past.lastOrNull() ?: shapes.dropLast(1)
        return Sketch(previous, target?.takeIf { previous.has(it) }, past.dropLast(1))
    }

    fun clear() = Sketch()

    private fun changed(next: List<SketchShape>, target: SideRef?) =
        if (next == shapes) copy(target = target) else Sketch(next, target, (past + listOf(shapes)).takeLast(UNDO_DEPTH))

    val totalArea: Double?
        get() = shapes.mapNotNull { (it as? Rect)?.area }.takeIf { it.isNotEmpty() }?.sum()

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

    fun dropShift(id: Long, dx: Float, dy: Float, reach: Float, step: Int? = GRID_MM): Pt {
        val moved = shapes.firstOrNull { it.id == id } ?: return Pt(0, 0)
        val others = shapes.filter { it.id != id }.flatMap { it.points }
        fun gap(from: Pt, to: Pt) = hypot(to.x - (from.x + dx), to.y - (from.y + dy))
        val join = moved.extent
            .flatMap { p -> others.map { p to it } }
            .filter { (p, q) -> gap(p, q) <= reach }
            .minByOrNull { (p, q) -> gap(p, q) }
        val (from, to) = join ?: run {
            val s = step?.takeIf { moved !is Note } ?: return Pt(dx.roundToInt(), dy.roundToInt())
            moved.extent
                .map { p -> p to Pt(((p.x + dx) / s).roundToInt() * s, ((p.y + dy) / s).roundToInt() * s) }
                .minBy { (p, q) -> gap(p, q) }
        }
        return to - from
    }

    fun shapeAt(x: Float, y: Float, tolerance: Float): Long? =
        sideAt(x, y, tolerance)?.shapeId ?: shapes.lastOrNull {
            it is Rect && x in minOf(it.a.x, it.b.x).toFloat()..maxOf(it.a.x, it.b.x).toFloat() &&
                y in minOf(it.a.y, it.b.y).toFloat()..maxOf(it.a.y, it.b.y).toFloat()
        }?.id

    fun sideAt(x: Float, y: Float, tolerance: Float): SideRef? = shapes
        .flatMap { s -> s.edges.map { SideRef(s.id, it.side) to distance(x, y, it) } }
        .filter { it.second <= tolerance }
        .minByOrNull { it.second }
        ?.first

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

private fun Edge.nearest(x: Float, y: Float): Pair<Float, Float> {
    val dx = (b.x - a.x).toFloat()
    val dy = (b.y - a.y).toFloat()
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0f, 1f)
    return a.x + t * dx to a.y + t * dy
}

private fun Edge.crossing(at: Float, vertical: Boolean): Pair<Float, Float>? {
    val (u0, u1) = if (vertical) a.x to b.x else a.y to b.y
    if (u0 == u1 || at < minOf(u0, u1) || at > maxOf(u0, u1)) return null
    val t = (at - u0) / (u1 - u0)
    return if (vertical) at to a.y + t * (b.y - a.y) else a.x + t * (b.x - a.x) to at
}

private fun List<SketchShape>.has(ref: SideRef) = any { it.id == ref.shapeId && ref.side in it.sides.indices }
