package dev.bezh.zamer

import dev.bezh.zamer.data.StoreJson
import dev.bezh.zamer.domain.Draft
import dev.bezh.zamer.domain.MeasureMode
import dev.bezh.zamer.domain.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTest {
    private fun Workspace.putAll(vararg mm: Int): Pair<Workspace, List<Long>> {
        var ws = this
        val saved = mutableListOf<Long>()
        mm.forEachIndexed { i, v ->
            val (next, result) = ws.put(v, entryId = 100L + i)
            if (result != null) saved += 100L + i
            ws = next
        }
        return ws to saved
    }

    @Test
    fun readingsFillSlotsInOrderAndTheLastOneCompletesTheSet() {
        val area = Workspace(mode = MeasureMode.AREA)
        val (half, _) = area.put(2000, 1)
        assertEquals(Draft(listOf(2000, null), next = 1), half.draft())
        assertNull(half.result())

        val (full, result) = half.put(3000, 2)
        assertEquals(listOf(2000, 3000), result!!.valuesMm)
        assertTrue(full.draft().isComplete)
        assertEquals(2L, full.draft().savedId)
    }

    @Test
    fun readingAfterACompleteSetStartsAFreshSet() {
        val (done, _) = Workspace(mode = MeasureMode.VOLUME).putAll(1000, 2000, 3000)
        val (next, result) = done.put(4000, 9)
        assertNull(result)
        assertEquals(Draft(listOf(4000, null, null), next = 1), next.draft())
    }

    @Test
    fun selectingASlotReMeasuresOnlyThatSide() {
        val (done, _) = Workspace(mode = MeasureMode.AREA).putAll(2000, 3000)
        val selected = done.select(0)
        assertEquals(Draft(listOf(2000, 3000), next = 0), selected.draft())
        assertNull("a set being re-measured shows no result", selected.result())

        val (again, result) = selected.put(2500, 7)
        assertEquals(listOf(2500, 3000), result!!.valuesMm)
        assertEquals(7L, again.draft().savedId)
    }

    @Test
    fun undoClearsTheLastFilledSlotAndWaitsForIt() {
        val (done, _) = Workspace(mode = MeasureMode.VOLUME).putAll(1000, 2000, 3000)
        assertEquals(Draft(listOf(1000, 2000, null), next = 2), done.undo().draft())
        assertEquals(Draft.empty(MeasureMode.VOLUME), Workspace(mode = MeasureMode.VOLUME).undo().draft())
    }

    @Test
    fun eachModeKeepsItsOwnDraft() {
        val (area, _) = Workspace(mode = MeasureMode.AREA).putAll(2000)
        val (volume, _) = area.copy(mode = MeasureMode.VOLUME).putAll(5000, 6000)
        val back = volume.copy(mode = MeasureMode.AREA)
        assertEquals(Draft(listOf(2000, null), next = 1), back.draft())
        assertEquals(Draft(listOf(5000, 6000, null), next = 2), back.draft(MeasureMode.VOLUME))

        val reset = back.reset()
        assertEquals(Draft.empty(MeasureMode.AREA), reset.draft())
        assertEquals(listOf(5000, 6000, null), reset.draft(MeasureMode.VOLUME).values)
    }

    @Test
    fun impossibleHeightCompletesWithoutSaving() {
        val (ws, saved) = Workspace(mode = MeasureMode.HEIGHT).putAll(5000, 3000)
        assertTrue(saved.isEmpty())
        assertTrue(ws.draft().isComplete)
        assertNull(ws.draft().savedId)
        assertNull(ws.result())

        val (fresh, _) = ws.put(3000, 50)
        assertEquals(Draft(listOf(3000, null), next = 1), fresh.draft())
    }

    @Test
    fun validHeightIsSaved() {
        val (ws, saved) = Workspace(mode = MeasureMode.HEIGHT).putAll(3000, 5000)
        assertEquals(listOf(101L), saved)
        assertEquals(4.0, ws.result()!!.primary, 1e-9)
    }

    @Test
    fun distanceSavesEveryReading() {
        val (_, saved) = Workspace(mode = MeasureMode.DISTANCE).putAll(1200, 3400)
        assertEquals(listOf(100L, 101L), saved)
    }

    @Test
    fun workspaceSurvivesAJsonRoundTrip() {
        val ws = Workspace(
            mode = MeasureMode.HEIGHT,
            drafts = mapOf(
                MeasureMode.DISTANCE to Draft(listOf(1234), next = null, savedId = 42),
                MeasureMode.AREA to Draft(listOf(2000, null), next = 1),
                MeasureMode.VOLUME to Draft(listOf(1000, 2000, 3000), next = 1),
                MeasureMode.HEIGHT to Draft(listOf(5000, 3000), next = null),
            ),
        )
        val decoded = StoreJson.decodeFromString<Workspace>(StoreJson.encodeToString(ws))
        assertEquals(ws, decoded)
    }

    @Test
    fun workspaceStoredByTheCurrentVersionDecodesAndEncodesUnchanged() {
        val stored = """{"mode":"HEIGHT","drafts":{""" +
            """"DISTANCE":{"values":[1234],"next":null,"savedId":42},""" +
            """"AREA":{"values":[2000,null],"next":1},""" +
            """"VOLUME":{"values":[1000,2000,3000],"next":1},""" +
            """"HEIGHT":{"values":[5000,3000],"next":null}}}"""
        val ws = StoreJson.decodeFromString<Workspace>(stored)
        assertEquals(MeasureMode.HEIGHT, ws.mode)
        assertEquals(Draft(listOf(1234), next = null, savedId = 42), ws.draft(MeasureMode.DISTANCE))
        assertEquals(Draft(listOf(2000, null), next = 1), ws.draft(MeasureMode.AREA))
        assertEquals(Draft(listOf(1000, 2000, 3000), next = 1), ws.draft(MeasureMode.VOLUME))
        assertEquals(Draft(listOf(5000, 3000), next = null), ws.draft(MeasureMode.HEIGHT))
        assertEquals(stored, StoreJson.encodeToString(ws))
    }

    @Test
    fun storedDraftWithTheWrongSizeReadsAsEmpty() {
        val ws = Workspace(mode = MeasureMode.AREA, drafts = mapOf(MeasureMode.AREA to Draft(listOf(1, 2, 3), next = 0)))
        assertEquals(Draft.empty(MeasureMode.AREA), ws.draft())
    }

    @Test
    fun inconsistentStoredDraftIsRejected() {
        val bad = """{"values":[1000,null],"next":null}"""
        assertFalse(runCatching { StoreJson.decodeFromString<Draft>(bad) }.isSuccess)
        assertNotNull(StoreJson.decodeFromString<Draft>("""{"values":[1000,null],"next":1}"""))
    }
}
