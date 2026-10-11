package dev.bezh.zamer

import dev.bezh.zamer.data.HistoryEntry
import dev.bezh.zamer.data.StoreJson
import dev.bezh.zamer.domain.MeasureMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryEntryJsonTest {
    @Test
    fun historySavedBeforeTitlesExistedStillDecodes() {
        val stored = """[{"id":17,"timestamp":1700000000000,"mode":"AREA","valuesMm":[2000,3000]}]"""
        assertEquals(
            listOf(HistoryEntry(17, 1700000000000, MeasureMode.AREA, listOf(2000, 3000), title = null)),
            StoreJson.decodeFromString<List<HistoryEntry>>(stored),
        )
    }

    @Test
    fun titleSurvivesARoundTrip() {
        val entry = HistoryEntry(1, 2, MeasureMode.HEIGHT, listOf(3000, 5000), title = "Ceiling, hall")
        assertEquals(entry, StoreJson.decodeFromString<HistoryEntry>(StoreJson.encodeToString(entry)))
    }
}
