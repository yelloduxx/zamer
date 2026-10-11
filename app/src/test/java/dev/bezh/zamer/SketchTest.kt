package dev.bezh.zamer

import dev.bezh.zamer.data.StoreJson
import dev.bezh.zamer.domain.Pt
import dev.bezh.zamer.domain.Rect
import dev.bezh.zamer.domain.Segment
import dev.bezh.zamer.domain.SideRef
import dev.bezh.zamer.domain.Sketch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SketchTest {
    private val line = Segment(1, Pt(0, 0), Pt(4, 0))
    private val room = Rect(2, Pt(0, 0), Pt(5, 3))

    @Test
    fun addedShapeWaitsForItsFirstSide() {
        val s = Sketch().add(line).add(room)
        assertEquals(listOf(line, room), s.shapes)
        assertEquals(SideRef(2, 0), s.target)
    }

    @Test
    fun readingMeasuresASegmentAndLeavesNothingWaiting() {
        val s = Sketch().add(line).put(2345)
        assertEquals(2345, (s.shapes.single() as Segment).mm)
        assertNull(s.target)
    }

    @Test
    fun rectangleTakesWidthThenHeight() {
        val width = Sketch().add(room).put(4000)
        assertEquals(SideRef(2, 1), width.target)
        val both = width.put(3000)
        assertEquals(room.copy(widthMm = 4000, heightMm = 3000), both.shapes.single())
        assertNull(both.target)
    }

    @Test
    fun selectedSideIsMeasuredAgainWithoutTouchingTheOthers() {
        val done = Sketch().add(room).put(4000).put(3000)
        val again = done.select(SideRef(2, 0)).put(4100)
        assertEquals(room.copy(widthMm = 4100, heightMm = 3000), again.shapes.single())
        assertNull("both sides are measured, so nothing waits", again.target)
    }

    @Test
    fun remeasuringWidthMovesOnToAnUnmeasuredHeight() {
        val s = Sketch().add(room).put(4000).add(line).select(SideRef(2, 0)).put(4100)
        assertEquals(SideRef(2, 1), s.target)
    }

    @Test
    fun readingWithNothingSelectedChangesNothing() {
        val s = Sketch().add(line).put(1000)
        assertSame(s, s.put(2000))
    }

    @Test
    fun selectingASideThatIsNotThereIsIgnored() {
        val s = Sketch().add(line).put(1000)
        assertSame(s, s.select(SideRef(1, 1)))
        assertSame(s, s.select(SideRef(99, 0)))
    }

    @Test
    fun deletingTheTargetShapeClearsTheTarget() {
        val s = Sketch().add(line).add(room)
        val gone = s.delete(2)
        assertEquals(listOf(line), gone.shapes)
        assertNull(gone.target)
        assertEquals("deleting another shape keeps the target", SideRef(2, 0), s.delete(1).target)
    }

    @Test
    fun undoRemovesTheLastShape() {
        val s = Sketch().add(line).put(1000).add(room)
        assertEquals(Sketch(listOf(line.copy(mm = 1000))), s.undo())
        val empty = Sketch()
        assertSame(empty, empty.undo())
        assertEquals(Sketch(), s.clear())
    }

    @Test
    fun shapesWithoutLengthOrAreaAreRejected() {
        val s = Sketch().add(line)
        assertSame(s, s.add(Segment(5, Pt(2, 2), Pt(2, 2))))
        assertSame(s, s.add(Rect(6, Pt(1, 1), Pt(1, 4))))
        assertSame(s, s.add(Rect(7, Pt(1, 1), Pt(4, 1))))
    }

    @Test
    fun areaCountsOnlyFullyMeasuredRectangles() {
        val half = Sketch().add(room).put(4000)
        assertNull(half.totalArea)
        val one = half.put(2500)
        assertEquals(10.0, (one.shapes.single() as Rect).area!!, 1e-9)
        val two = one.add(Rect(3, Pt(10, 0), Pt(12, 2))).put(2000).put(1500).add(line).put(9000)
        assertEquals(13.0, two.totalArea!!, 1e-9)
    }

    @Test
    fun drawingSnapsToANearbyCornerBeforeTheNearestDot() {
        val s = Sketch().add(room)
        assertEquals(Pt(5, 3), s.snap(5.8f, 3.4f))
        assertEquals(Pt(8, 1), s.snap(7.6f, 1.2f))
    }

    @Test
    fun tapPicksTheNearestSideWithinReach() {
        val s = Sketch().add(room)
        assertEquals(SideRef(2, 0), s.sideAt(2f, 2.8f, 0.5f))
        assertEquals(SideRef(2, 1), s.sideAt(5.2f, 1f, 0.5f))
        assertNull(s.sideAt(2.5f, 1.5f, 0.5f))
    }

    @Test
    fun sketchSurvivesAJsonRoundTrip() {
        val s = Sketch().add(line).put(2345).add(room).put(4000)
        assertEquals(s, StoreJson.decodeFromString<Sketch>(StoreJson.encodeToString(s)))

        val stored = """{"shapes":[""" +
            """{"type":"segment","id":1,"a":{"x":0,"y":0},"b":{"x":4,"y":0},"mm":2345},""" +
            """{"type":"rect","id":2,"a":{"x":0,"y":0},"b":{"x":5,"y":3},"widthMm":4000}""" +
            """],"target":{"shapeId":2,"side":1}}"""
        assertEquals(s, StoreJson.decodeFromString<Sketch>(stored))
    }
}
