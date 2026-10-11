package dev.bezh.zamer.domain

import kotlinx.serialization.Serializable
import java.util.Locale

/** What the user wants to work out; [slots] is how many readings it takes. */
@Serializable
enum class MeasureMode(val slots: Int) {
    DISTANCE(1),
    AREA(2),
    VOLUME(3),
}

enum class UnitSystem { METRIC, IMPERIAL }

/** Everything derivable from one completed set of readings (all in millimetres). */
data class MeasureResult(val mode: MeasureMode, val valuesMm: List<Int>) {
    private val m = valuesMm.map { it / 1000.0 }

    /** Metres, square metres or cubic metres depending on [mode]. */
    val primary: Double
        get() = when (mode) {
            MeasureMode.DISTANCE -> m[0]
            MeasureMode.AREA -> m[0] * m[1]
            MeasureMode.VOLUME -> m[0] * m[1] * m[2]
        }

    /** Floor area of a volume, or perimeter of an area. */
    val secondary: Double?
        get() = when (mode) {
            MeasureMode.DISTANCE -> null
            MeasureMode.AREA -> 2 * (m[0] + m[1])
            MeasureMode.VOLUME -> m[0] * m[1]
        }
}

/** A number and its unit, kept apart so the UI can size them differently. */
data class Quantity(val number: String, val unit: String) {
    override fun toString() = "$number $unit"
}

/** Renders values according to the user's unit and precision settings. */
class Formatter(
    private val units: UnitSystem,
    private val decimals: Int,
    private val locale: Locale = Locale.getDefault(),
) {
    private val ftPerM = 3.280839895

    fun length(mm: Int): Quantity = lengthFromMetres(mm / 1000.0)

    fun lengthFromMetres(metres: Double): Quantity = when (units) {
        UnitSystem.METRIC -> Quantity(fmt(metres, decimals), "m")
        UnitSystem.IMPERIAL -> Quantity(fmt(metres * ftPerM, minOf(decimals, 2)), "ft")
    }

    fun area(squareMetres: Double): Quantity = when (units) {
        UnitSystem.METRIC -> Quantity(fmt(squareMetres, 2), "m²")
        UnitSystem.IMPERIAL -> Quantity(fmt(squareMetres * ftPerM * ftPerM, 2), "ft²")
    }

    fun volume(cubicMetres: Double): Quantity = when (units) {
        UnitSystem.METRIC -> Quantity(fmt(cubicMetres, 3), "m³")
        UnitSystem.IMPERIAL -> Quantity(fmt(cubicMetres * ftPerM * ftPerM * ftPerM, 2), "ft³")
    }

    fun primary(r: MeasureResult): Quantity = when (r.mode) {
        MeasureMode.DISTANCE -> lengthFromMetres(r.primary)
        MeasureMode.AREA -> area(r.primary)
        MeasureMode.VOLUME -> volume(r.primary)
    }

    fun secondary(r: MeasureResult): Quantity? = r.secondary?.let {
        when (r.mode) {
            MeasureMode.AREA -> lengthFromMetres(it)
            MeasureMode.VOLUME -> area(it)
            MeasureMode.DISTANCE -> null
        }
    }

    private fun fmt(v: Double, digits: Int) = String.format(locale, "%.${digits}f", v)
}
