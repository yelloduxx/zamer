package dev.bezh.zamer

import dev.bezh.zamer.data.StoreJson
import dev.bezh.zamer.data.decodeSketch
import dev.bezh.zamer.domain.GRID_MM
import dev.bezh.zamer.domain.Guide
import dev.bezh.zamer.domain.Pt
import dev.bezh.zamer.domain.Snap
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
    fun undoStepsBackOverEachDrawingAndReading() {
        val s = Sketch().add(line).put(1000).add(room)
        val noRoom = s.undo()
        assertEquals(listOf(line.copy(b = Pt(1000, 0), mm = 1000)), noRoom.shapes)
        assertNull("the room it waited for is gone", noRoom.target)
        assertEquals("the reading is undone with the length it gave", listOf(line), noRoom.undo().shapes)
        assertEquals(Sketch(), noRoom.undo().undo())
        assertEquals(Sketch(), Sketch().undo())
        assertEquals(Sketch(), s.clear())
    }

    @Test
    fun undoInASketchWithNoRememberedChangesRemovesTheLastShape() {
        val saved = Sketch(listOf(line, room), SideRef(2, 1))
        assertEquals(Sketch(listOf(line)), saved.undo())
    }

    @Test
    fun movingAShapeLeavesItsMeasurementsAndNeighboursAndCanBeUndone() {
        val wall = Segment(3, Pt(2500, 0), Pt(2500, -1000))
        val s = Sketch().add(room).put(2500).put(1500).add(wall)
        val moved = s.move(2, Pt(-500, 3000))
        assertEquals(
            listOf(Rect(2, Pt(-500, 3000), Pt(2000, 4500), 2500, 1500), wall),
            moved.shapes,
        )
        assertEquals(SideRef(3, 0), moved.target)
        assertEquals(s.shapes, moved.undo().shapes)
        assertEquals("not moving is not a change", s, s.move(2, Pt(0, 0)))
    }

    @Test
    fun aDraggedShapeDropsOntoANearbyEndBeforeTheNearestDot() {
        val s = Sketch()
            .add(Segment(1, Pt(0, 0), Pt(1234, 0)))
            .add(Segment(2, Pt(0, 1000), Pt(1000, 1000)))
        assertEquals("its start lands on the other line's end", Pt(1234, -1000), s.dropShift(2, 1200f, -950f, reach = 300f))
        assertEquals(Pt(500, 0), s.dropShift(2, 480f, 30f, reach = 300f))
        assertEquals(Pt(1000, 0), s.dropShift(2, 880f, 30f, reach = 100f, step = 1000))

        val offGrid = Sketch().add(Segment(1, Pt(100, 0), Pt(1100, 0)))
        assertEquals("one of its points lands on a dot", Pt(400, 0), offGrid.dropShift(1, 380f, 0f, reach = 0f))
        assertEquals("its own old place is no end to join", Pt(-100, 0), offGrid.dropShift(1, 30f, 30f, reach = 300f))
    }

    @Test
    fun aShapeIsPickedByItsSidesOrInsideARectangle() {
        val s = Sketch().add(room).add(Segment(1, Pt(5000, 0), Pt(6000, 0)))
        assertEquals(2L, s.shapeAt(1250f, 750f, 250f))
        assertEquals(1L, s.shapeAt(5500f, 200f, 250f))
        assertNull(s.shapeAt(4000f, 2000f, 250f))
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
        assertEquals(Snap(Pt(2500, 1500)), s.snap(2900f, 1700f, reach = 500f, align = 0f, step = GRID_MM))
        assertEquals(Snap(Pt(4000, 500)), s.snap(3800f, 600f, reach = 500f, align = 0f, step = GRID_MM))
        assertEquals("dots shown farther apart are snapped to instead", Pt(4000, 1000), s.snap(3800f, 600f, 500f, 0f, step = 1000).at)
        assertEquals("with no grid the finger stays where it is", Pt(3800, 601), s.snap(3800f, 600.6f, 500f, 0f, step = null).at)
    }

    @Test
    fun aNearlyVerticalStrokeFromAnOffGridCornerComesOutExactlyVertical() {
        val s = Sketch().add(Segment(1, Pt(0, 0), Pt(1000, 0))).put(1234)
        val corner = Pt(1234, 0)
        val end = s.snap(1290f, 1980f, reach = 100f, align = 75f, step = GRID_MM, from = corner)
        assertEquals(Snap(Pt(1234, 2000), listOf(Guide(corner, vertical = true))), end)

        val sideways = s.snap(3020f, 40f, reach = 100f, align = 75f, step = GRID_MM, from = corner)
        assertEquals("a nearly level stroke stays level", Pt(3000, 0), sideways.at)
        assertEquals("without the start to line up with, the corner's own x is used", Pt(1234, 2000), s.snap(1290f, 1980f, 100f, 75f, GRID_MM).at)
    }

    @Test
    fun aStrokeEndingNearAWallButtsIntoIt() {
        val s = Sketch().add(Segment(1, Pt(0, 0), Pt(1000, 1000)))
        val t = s.snap(620f, 380f, reach = 200f, align = 0f, step = GRID_MM)
        assertEquals("the nearest point of the diagonal", Snap(Pt(500, 500)), t)

        val wall = Sketch().add(Segment(1, Pt(0, 3000), Pt(4000, 3000)))
        val start = Pt(1234, 0)
        val down = wall.snap(1260f, 2915f, reach = 100f, align = 75f, step = GRID_MM, from = start)
        assertEquals("a vertical line stops exactly on the wall", Snap(Pt(1234, 3000), listOf(Guide(start, vertical = true))), down)
    }

    @Test
    fun snappingTriesCornersThenTheStartThenOtherPointsThenSidesThenDots() {
        val s = Sketch()
            .add(Segment(1, Pt(0, 0), Pt(1000, 0)))
            .add(Segment(2, Pt(1100, 2000), Pt(3000, 2000)))
        val from = Pt(1000, 0)
        assertEquals("a corner within reach beats lining up", Pt(1100, 2000), s.snap(1040f, 1990f, 100f, 75f, GRID_MM, from).at)
        assertEquals(
            "the start beats another point at the same distance",
            Snap(Pt(1000, 1500), listOf(Guide(from, vertical = true))),
            s.snap(1050f, 1450f, 100f, 75f, GRID_MM, from),
        )
        assertEquals(
            "another point's x beats the dot",
            Snap(Pt(1100, 1500), listOf(Guide(Pt(1100, 2000), vertical = true))),
            s.snap(1080f, 1460f, 100f, 75f, GRID_MM),
        )
        assertEquals("a side beats the dot", Pt(2040, 2000), s.snap(2040f, 1920f, 100f, 75f, GRID_MM).at)
        assertEquals("nothing near leaves the dot", Snap(Pt(2000, 1000)), s.snap(2040f, 1100f, 100f, 75f, GRID_MM))
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
        assertEquals(Sketch(s.shapes, s.target), StoreJson.decodeFromString<Sketch>(stored))
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
