package dev.bezh.zamer

import dev.bezh.zamer.domain.Formatter
import dev.bezh.zamer.domain.MeasureMode
import dev.bezh.zamer.domain.MeasureResult
import dev.bezh.zamer.domain.Quantity
import dev.bezh.zamer.domain.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class MeasureResultTest {
    private val metric = Formatter(UnitSystem.METRIC, 3, Locale.US)

    @Test
    fun heightIsTheMissingLegOfTheTriangle() {
        val r = MeasureResult.of(MeasureMode.HEIGHT, listOf(3000, 5000))!!
        assertEquals(4.0, r.primary, 1e-9)
        assertNull(r.secondary)
        assertEquals(Quantity("4.000", "m"), metric.primary(r))
    }

    @Test
    fun slopeNotLongerThanBaseHasNoHeight() {
        assertNull(MeasureResult.of(MeasureMode.HEIGHT, listOf(5000, 5000)))
        assertNull(MeasureResult.of(MeasureMode.HEIGHT, listOf(5000, 3000)))
    }

    @Test
    fun areaAndVolumeKeepTheirSecondaryValues() {
        val area = MeasureResult.of(MeasureMode.AREA, listOf(2000, 3500))!!
        assertEquals(7.0, area.primary, 1e-9)
        assertEquals(11.0, area.secondary!!, 1e-9)

        val volume = MeasureResult.of(MeasureMode.VOLUME, listOf(2000, 3000, 2500))!!
        assertEquals(15.0, volume.primary, 1e-9)
        assertEquals(6.0, volume.secondary!!, 1e-9)
    }

    @Test
    fun wrongNumberOfValuesHasNoResult() {
        assertNull(MeasureResult.of(MeasureMode.AREA, listOf(2000)))
        assertNull(MeasureResult.of(MeasureMode.DISTANCE, emptyList()))
    }
}
