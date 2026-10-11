package dev.bezh.zamer

import dev.bezh.zamer.data.StoreJson
import dev.bezh.zamer.data.decodeSketch
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
    private val line = Segment(1, Pt(0, 0), Pt(2000, 0))
    private val room = Rect(2, Pt(0, 0), Pt(2500, 1500))

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
        assertEquals(room.copy(b = Pt(4000, 3000), widthMm = 4000, heightMm = 3000), both.shapes.single())
        assertNull(both.target)
    }

    @Test
    fun selectedSideIsMeasuredAgainWithoutTouchingTheOthers() {
        val done = Sketch().add(room).put(4000).put(3000)
        val again = done.select(SideRef(2, 0)).put(4100)
        assertEquals(room.copy(b = Pt(4100, 3000), widthMm = 4100, heightMm = 3000), again.shapes.single())
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
        assertEquals(Sketch(listOf(line.copy(b = Pt(1000, 0), mm = 1000))), s.undo())
        val empty = Sketch()
        assertSame(empty, empty.undo())
        assertEquals(Sketch(), s.clear())
    }

    @Test
    fun shapesWithoutLengthOrAreaAreRejected() {
        val s = Sketch().add(line)
        assertSame(s, s.add(Segment(5, Pt(1000, 1000), Pt(1000, 1000))))
        assertSame(s, s.add(Rect(6, Pt(500, 500), Pt(500, 2000))))
        assertSame(s, s.add(Rect(7, Pt(500, 500), Pt(2000, 500))))
    }

    @Test
    fun areaCountsOnlyFullyMeasuredRectangles() {
        val half = Sketch().add(room).put(4000)
        assertNull(half.totalArea)
        val one = half.put(2500)
        assertEquals(10.0, (one.shapes.single() as Rect).area!!, 1e-9)
        val two = one.add(Rect(3, Pt(5000, 0), Pt(6000, 1000))).put(2000).put(1500).add(line).put(9000)
        assertEquals(13.0, two.totalArea!!, 1e-9)
    }

    @Test
    fun measuringASegmentRedrawsItThatLongFromItsStart() {
        val s = Sketch().add(Segment(1, Pt(0, 0), Pt(1000, 0))).put(4000)
        assertEquals(Segment(1, Pt(0, 0), Pt(4000, 0), mm = 4000), s.shapes.single())

        val diagonal = Sketch().add(Segment(1, Pt(0, 0), Pt(1000, 1000))).put(2000).shapes.single() as Segment
        assertEquals("1414.2 mm each way keeps 45 degrees", Pt(1414, 1414), diagonal.b)

        val back = Sketch().add(Segment(1, Pt(500, 500), Pt(-500, 1500))).put(1000).shapes.single() as Segment
        assertEquals(Pt(500, 500), back.a)
        assertEquals(Pt(-207, 1207), back.b)

        val again = s.select(SideRef(1, 0)).put(1500)
        assertEquals("measuring again redraws again", Pt(1500, 0), (again.shapes.single() as Segment).b)
    }

    @Test
    fun measuringARectangleMovesTheCornerAwayFromItsStart() {
        val leftUp = Rect(1, Pt(3000, 2000), Pt(1000, 0))
        val width = Sketch().add(leftUp).put(4000)
        assertEquals(Rect(1, Pt(3000, 2000), Pt(-1000, 0), widthMm = 4000), width.shapes.single())
        val both = width.put(1000)
        assertEquals(Rect(1, Pt(3000, 2000), Pt(-1000, 1000), 4000, 1000), both.shapes.single())
        val narrower = both.select(SideRef(1, 0)).put(500)
        assertEquals(Rect(1, Pt(3000, 2000), Pt(2500, 1000), 500, 1000), narrower.shapes.single())
    }

    @Test
    fun anOpenChainOfWallsSlidesAlongWithTheMeasuredOne() {
        val s = Sketch()
            .add(Segment(1, Pt(0, 0), Pt(1000, 0)))
            .add(Segment(2, Pt(1000, 0), Pt(1000, 1000)))
            .add(Segment(3, Pt(1000, 1000), Pt(2000, 1000), mm = 1000))
            .add(Segment(4, Pt(-1000, 0), Pt(0, 0)))
            .select(SideRef(1, 0))
            .put(4000)
        assertEquals(
            listOf(
                Segment(1, Pt(0, 0), Pt(4000, 0), mm = 4000),
                Segment(2, Pt(4000, 0), Pt(4000, 1000)),
                Segment(3, Pt(4000, 1000), Pt(5000, 1000), mm = 1000),
                Segment(4, Pt(-1000, 0), Pt(0, 0)),
            ),
            s.shapes,
        )
    }

    @Test
    fun aClosedLoopStretchesTheWallThatClosesItAndKeepsTheStartsNeighbour() {
        val s = Sketch()
            .add(Segment(1, Pt(0, 0), Pt(1000, 0)))
            .add(Segment(2, Pt(1000, 0), Pt(1000, 1000)))
            .add(Segment(3, Pt(1000, 1000), Pt(0, 1000), mm = 1000))
            .add(Segment(4, Pt(0, 1000), Pt(0, 0)))
            .select(SideRef(1, 0))
            .put(4000)
        assertEquals(
            listOf(
                Segment(1, Pt(0, 0), Pt(4000, 0), mm = 4000),
                Segment(2, Pt(4000, 0), Pt(4000, 1000)),
                Segment(3, Pt(4000, 1000), Pt(0, 1000), mm = 1000),
                Segment(4, Pt(0, 1000), Pt(0, 0)),
            ),
            s.shapes,
        )
    }

    @Test
    fun aRectangleJoinedAtAMovedPointSlidesAndPullsWhatHangsOffItsCorners() {
        val s = Sketch()
            .add(Segment(1, Pt(0, 0), Pt(1000, 0)))
            .add(Rect(2, Pt(1000, 0), Pt(2000, 1000), widthMm = 1000))
            .add(Segment(3, Pt(1000, 1000), Pt(1000, 2000)))
            .select(SideRef(1, 0))
            .put(2000)
        assertEquals(
            listOf(
                Segment(1, Pt(0, 0), Pt(2000, 0), mm = 2000),
                Rect(2, Pt(2000, 0), Pt(3000, 1000), widthMm = 1000),
                Segment(3, Pt(2000, 1000), Pt(2000, 2000)),
            ),
            s.shapes,
        )
    }

    @Test
    fun aRectangleHeldAtBothEndsOfTheMeasuredSideIsNotSkewed() {
        val box = Rect(2, Pt(0, 0), Pt(1000, -1000))
        val s = Sketch().add(Segment(1, Pt(0, 0), Pt(1000, 0))).add(box).select(SideRef(1, 0)).put(3000)
        assertEquals(listOf(Segment(1, Pt(0, 0), Pt(3000, 0), mm = 3000), box), s.shapes)
    }

    @Test
    fun drawingSnapsToANearbyCornerBeforeTheNearestDot() {
        val s = Sketch().add(room)
        assertEquals(Pt(2500, 1500), s.snap(2900f, 1700f, reach = 500f))
        assertEquals(Pt(4000, 500), s.snap(3800f, 600f, reach = 500f))
        assertEquals("dots shown farther apart are snapped to instead", Pt(4000, 1000), s.snap(3800f, 600f, 500f, step = 1000))
    }

    @Test
    fun tapPicksTheNearestSideWithinReach() {
        val s = Sketch().add(room)
        assertEquals(SideRef(2, 0), s.sideAt(1000f, 1400f, 250f))
        assertEquals(SideRef(2, 1), s.sideAt(2600f, 500f, 250f))
        assertNull(s.sideAt(1250f, 750f, 250f))
    }

    @Test
    fun sketchSurvivesAJsonRoundTrip() {
        val s = Sketch().add(line).put(2345).add(room).put(4000)
        assertEquals(s, StoreJson.decodeFromString<Sketch>(StoreJson.encodeToString(s)))

        val stored = """{"shapes":[""" +
            """{"type":"segment","id":1,"a":{"x":0,"y":0},"b":{"x":2345,"y":0},"mm":2345},""" +
            """{"type":"rect","id":2,"a":{"x":0,"y":0},"b":{"x":4000,"y":1500},"widthMm":4000}""" +
            """],"target":{"shapeId":2,"side":1}}"""
        assertEquals(s, StoreJson.decodeFromString<Sketch>(stored))
    }

    @Test
    fun sketchSavedInDotSpacingsLoadsInMillimetres() {
        val grid = """{"shapes":[""" +
            """{"type":"segment","id":1,"a":{"x":1,"y":2},"b":{"x":5,"y":2},"mm":2345},""" +
            """{"type":"rect","id":2,"a":{"x":0,"y":0},"b":{"x":-3,"y":4},"widthMm":4000}""" +
            """],"target":{"shapeId":2,"side":1}}"""
        val expected = Sketch(
            listOf(
                Segment(1, Pt(500, 1000), Pt(2500, 1000), mm = 2345),
                Rect(2, Pt(0, 0), Pt(-1500, 2000), widthMm = 4000),
            ),
            SideRef(2, 1),
        )
        assertEquals(expected, decodeSketch(mm = null, grid = grid))

        val current = StoreJson.encodeToString(Sketch().add(line))
        assertEquals("a sketch already in millimetres wins", Sketch().add(line), decodeSketch(current, grid))
        assertEquals(Sketch(), decodeSketch(null, null))
        assertEquals("unreadable data starts afresh", Sketch(), decodeSketch(null, "{not json"))
    }
}
