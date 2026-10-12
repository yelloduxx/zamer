package dev.bezh.zamer

import dev.bezh.zamer.data.StoreJson
import dev.bezh.zamer.domain.Note
import dev.bezh.zamer.domain.Pt
import dev.bezh.zamer.domain.Segment
import dev.bezh.zamer.domain.SideRef
import dev.bezh.zamer.domain.Sketch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NoteTest {
    private val wall = Segment(1, Pt(0, 0), Pt(2000, 0))
    private val note = Note(2, Pt(1000, 700), "Socket")
    private val drawn = Sketch().add(wall)

    @Test
    fun aNoteIsAddedWithoutTakingTheNextReading() {
        val s = drawn.add(note)
        assertEquals(listOf(wall, note), s.shapes)
        assertEquals(SideRef(1, 0), s.target)
        assertEquals("a reading still measures the wall", 1500, s.put(1500).shapes.filterIsInstance<Segment>().single().mm)
        assertEquals(note, s.put(1500).shapes.filterIsInstance<Note>().single())
    }

    @Test
    fun aNoteWithoutTextIsNotAdded() {
        assertSame(drawn, drawn.add(Note(2, Pt(0, 0), "  ")))
    }

    @Test
    fun aNoteIsDeletedAndTheDeletionUndone() {
        val s = drawn.add(note)
        val gone = s.delete(note.id)
        assertEquals(listOf(wall), gone.shapes)
        assertEquals(SideRef(1, 0), gone.target)
        assertEquals(s.shapes, gone.undo().shapes)
        assertEquals(drawn.shapes, s.undo().shapes)
    }

    @Test
    fun rewritingANoteCanBeUndoneButBlankTextChangesNothing() {
        val s = drawn.add(note)
        val renamed = s.write(note.id, " Two sockets ")
        assertEquals(note.copy(text = "Two sockets"), renamed.shapes.last())
        assertEquals(note, renamed.undo().shapes.last())
        assertSame(s, s.write(note.id, ""))
    }

    @Test
    fun aDraggedNoteDropsWhereItIsLeftOffTheGridOrOnAPointNearby() {
        val s = drawn.add(note)
        assertEquals("no grid for a note", Pt(130, -70), s.dropShift(note.id, 130f, -70f, reach = 100f))
        assertEquals("onto the wall's end", Pt(1000, -700), s.dropShift(note.id, 960f, -650f, reach = 100f))
        val moved = s.move(note.id, Pt(130, -70))
        assertEquals(Pt(1130, 630), (moved.shapes.last() as Note).at)
        assertEquals(s.shapes, moved.undo().shapes)
    }

    @Test
    fun aWallIsNeverDrawnToANoteOrDroppedOntoIt() {
        val s = drawn.add(note)
        assertEquals("a finger by the note stays on the grid", Pt(1000, 500), s.snap(1010f, 690f, reach = 100f, align = 0f, step = 500).at)
        val stub = Segment(3, Pt(3000, 3000), Pt(3500, 3000))
        assertEquals(Pt(-2000, -2500), s.add(stub).dropShift(3, -1990f, -2290f, reach = 100f))
    }

    @Test
    fun aNoteSurvivesAJsonRoundTrip() {
        val s = drawn.add(note)
        val json = StoreJson.encodeToString(s)
        assert("\"type\":\"note\"" in json)
        assertEquals(s, StoreJson.decodeFromString<Sketch>(json))
    }
}
