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

    @Test
    fun typedMetresAcceptACommaOrADot() {
        assertEquals(4000, metric.parseLength("4"))
        assertEquals(4250, metric.parseLength("4,25"))
        assertEquals(4250, metric.parseLength("4.25"))
        assertEquals(500, metric.parseLength(" 0,5 "))
        assertEquals(500, metric.parseLength(",5"))
        assertEquals(3000, metric.parseLength("3."))
    }

    @Test
    fun typedFeetBecomeMillimetres() {
        val imperial = Formatter(UnitSystem.IMPERIAL, 2, Locale.US)
        assertEquals(3048, imperial.parseLength("10"))
        assertEquals(457, imperial.parseLength("1,5"))
    }

    @Test
    fun typedTextThatIsNotALengthIsRefused() {
        listOf("", "  ", "0", "0,0", "0.0001", "-1", "abc", "4m", "4,2,5", "1e3", "NaN", "Infinity", "2000")
            .forEach { assertNull(it, metric.parseLength(it)) }
    }

    @Test
    fun aShownLengthReadsBackAsTheSameMillimetres() {
        val russian = Formatter(UnitSystem.METRIC, 3, Locale.forLanguageTag("ru"))
        assertEquals("4,250", russian.length(4250).number)
        assertEquals(4250, russian.parseLength(russian.length(4250).number))
    }
}
