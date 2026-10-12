package dev.bezh.zamer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import dev.bezh.zamer.data.SKETCH
import dev.bezh.zamer.data.SKETCHES
import dev.bezh.zamer.data.SketchListMigration
import dev.bezh.zamer.data.StoreJson
import dev.bezh.zamer.data.migratedSketches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import dev.bezh.zamer.domain.Background
import dev.bezh.zamer.domain.Pt
import dev.bezh.zamer.domain.Rect
import dev.bezh.zamer.domain.Segment
import dev.bezh.zamer.domain.SideRef
import dev.bezh.zamer.domain.Sketch
import dev.bezh.zamer.domain.SketchDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SketchDocTest {
    private val oldMm = """{"shapes":[""" +
        """{"type":"segment","id":1,"a":{"x":0,"y":0},"b":{"x":2345,"y":0},"mm":2345},""" +
        """{"type":"rect","id":2,"a":{"x":0,"y":0},"b":{"x":4000,"y":1500},"widthMm":4000}""" +
        """],"target":{"shapeId":2,"side":1},"past":[[""" +
        """{"type":"segment","id":1,"a":{"x":0,"y":0},"b":{"x":2000,"y":0}}""" +
        """]]}"""

    @Test
    fun theOneSketchOfAnEarlierVersionBecomesTheFirstOfTheList() {
        val segment = Segment(1, Pt(0, 0), Pt(2345, 0), mm = 2345)
        val expected = SketchDoc(
            id = 1_700_000_000_000,
            created = 1_700_000_000_000,
            name = null,
            background = Background.Plan,
            sketch = Sketch(
                shapes = listOf(segment, Rect(2, Pt(0, 0), Pt(4000, 1500), widthMm = 4000)),
                target = SideRef(2, 1),
                past = listOf(listOf(Segment(1, Pt(0, 0), Pt(2000, 0)))),
            ),
        )
        assertEquals(listOf(expected), migratedSketches(oldMm, grid = null, now = 1_700_000_000_000))

        val grid = """{"shapes":[{"type":"segment","id":1,"a":{"x":1,"y":2},"b":{"x":5,"y":2}}]}"""
        assertEquals(
            "one counted in dot spacings is scaled to millimetres",
            listOf(Segment(1, Pt(500, 1000), Pt(2500, 1000))),
            migratedSketches(null, grid, now = 5).single().sketch.shapes,
        )
        assertEquals("nothing drawn leaves the list empty", emptyList<SketchDoc>(), migratedSketches("""{"shapes":[]}""", null, 5))
        assertEquals(emptyList<SketchDoc>(), migratedSketches(null, null, 5))
        assertEquals("unreadable data is dropped", emptyList<SketchDoc>(), migratedSketches("{not json", null, 5))
    }

    @Test
    fun theStoreMovesTheOldSketchIntoTheListBeforeAnythingReadsIt() = runBlocking {
        val file = File.createTempFile("zamer", ".preferences_pb").apply { delete() }
        val oldScope = CoroutineScope(Dispatchers.IO + Job())
        val old = PreferenceDataStoreFactory.create(scope = oldScope) { file }
        old.edit { it[SKETCH] = oldMm }
        oldScope.coroutineContext[Job]!!.cancelAndJoin()

        val scope = CoroutineScope(Dispatchers.IO + Job())
        val store = PreferenceDataStoreFactory.create(migrations = listOf(SketchListMigration), scope = scope) { file }
        val p = store.data.first()
        val docs = StoreJson.decodeFromString<List<SketchDoc>>(p[SKETCHES]!!)
        assertEquals(2, docs.single().sketch.shapes.size)
        assertNull("the old key is gone, so it migrates once", p[SKETCH])
        scope.coroutineContext[Job]!!.cancelAndJoin()
        file.delete()
        Unit
    }

    @Test
    fun sketchesSurviveAJsonRoundTrip() {
        val docs = listOf(
            SketchDoc(2, 200, "Kitchen", Background.Photo("a.jpg", 2560, 1920), Sketch().add(Segment(5, Pt(10, 10), Pt(900, 10)))),
            SketchDoc(1, 100),
        )
        assertEquals(docs, StoreJson.decodeFromString<List<SketchDoc>>(StoreJson.encodeToString(docs)))
    }

    @Test
    fun aMeasuredSideOnAPlanIsRedrawnButOnAPhotoOnlyLabelled() {
        val wall = Segment(1, Pt(100, 100), Pt(600, 100))
        val next = Segment(2, Pt(600, 100), Pt(600, 400))
        val plan = SketchDoc(1, 1, sketch = Sketch().add(next).add(wall)).put(3000)
        assertEquals(Segment(1, Pt(100, 100), Pt(3100, 100), mm = 3000), plan.sketch.shapes[1])
        assertEquals("the joined wall follows on a plan", Pt(3100, 100), (plan.sketch.shapes[0] as Segment).a)

        val photo = SketchDoc(1, 1, background = Background.Photo("p.jpg", 4000, 3000), sketch = Sketch().add(next).add(wall)).put(3000)
        assertEquals(listOf(next, wall.copy(mm = 3000)), photo.sketch.shapes)

        val room = Rect(3, Pt(0, 0), Pt(800, 600))
        val labelled = SketchDoc(1, 1, background = Background.Photo("p.jpg", 4000, 3000), sketch = Sketch().add(room)).put(4000).put(2500)
        assertEquals(room.copy(widthMm = 4000, heightMm = 2500), labelled.sketch.shapes.single())
        assertEquals("a photo still adds up the area", 10.0, labelled.sketch.totalArea!!, 1e-9)
        assertEquals("labelling can be undone", listOf(room.copy(widthMm = 4000)), labelled.sketch.undo().shapes)
    }

    @Test
    fun aPhotoHasNoGridSoAShapeDropsWhereItIsLeft() {
        val s = Sketch().add(Segment(1, Pt(100, 0), Pt(1100, 0)))
        assertEquals(Pt(380, 7), s.dropShift(1, 380.2f, 6.9f, reach = 0f, step = null))
        assertNull(SketchDoc(1, 1, background = Background.Photo("p.jpg", 10, 10)).grid)
    }

    @Test
    fun aBlankNameRemovesIt() {
        val doc = SketchDoc(1, 1)
        assertEquals("Hall", doc.renamed("  Hall ").name)
        assertNull(doc.renamed("Hall").renamed("   ").name)
    }
}
