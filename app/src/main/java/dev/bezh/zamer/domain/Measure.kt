package dev.bezh.zamer.domain

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Side { LENGTH, WIDTH, HEIGHT, BASE, HYPOTENUSE }

@Serializable
enum class MeasureMode(vararg sides: Side) {
    DISTANCE(Side.LENGTH),
    AREA(Side.LENGTH, Side.WIDTH),
    VOLUME(Side.LENGTH, Side.WIDTH, Side.HEIGHT),

    HEIGHT(Side.BASE, Side.HYPOTENUSE),
    ;

    val sides: List<Side> = sides.toList()
}

enum class UnitSystem { METRIC, IMPERIAL }

@Serializable
data class Reading(val mm: Int, val at: Long)

@ConsistentCopyVisibility
data class MeasureResult private constructor(
    val mode: MeasureMode,
    val valuesMm: List<Int>,
    val primary: Double,
    val secondary: Double?,
) {
    companion object {
        fun of(mode: MeasureMode, valuesMm: List<Int>): MeasureResult? {
            if (valuesMm.size != mode.sides.size) return null
            val m = valuesMm.map { it / 1000.0 }
            return when (mode) {
                MeasureMode.DISTANCE -> MeasureResult(mode, valuesMm, m[0], null)
                MeasureMode.AREA -> MeasureResult(mode, valuesMm, m[0] * m[1], 2 * (m[0] + m[1]))
                MeasureMode.VOLUME -> MeasureResult(mode, valuesMm, m[0] * m[1] * m[2], m[0] * m[1])
                MeasureMode.HEIGHT ->
                    if (valuesMm[1] <= valuesMm[0]) null
                    else MeasureResult(mode, valuesMm, sqrt(m[1] * m[1] - m[0] * m[0]), null)
            }
        }
    }
}

data class Quantity(val number: String, val unit: String) {
    override fun toString() = "$number$NO_BREAK_SPACE$unit"
}

private const val NO_BREAK_SPACE = '\u00A0'

class Formatter(
    private val units: UnitSystem,
    private val decimals: Int,
    private val locale: Locale = Locale.getDefault(),
) {
    private val ftPerM = 3.280839895

    fun length(mm: Int): Quantity = lengthFromMetres(mm / 1000.0)

    fun parseLength(text: String): Int? {
        val number = text.trim().replace(',', '.').takeIf { Typed.matches(it) }?.toDouble() ?: return null
        val mm = (number * if (units == UnitSystem.METRIC) 1000.0 else MM_PER_FT).roundToInt()
        return mm.takeIf { it in 1..MAX_TYPED_MM }
    }

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
        MeasureMode.DISTANCE, MeasureMode.HEIGHT -> lengthFromMetres(r.primary)
        MeasureMode.AREA -> area(r.primary)
        MeasureMode.VOLUME -> volume(r.primary)
    }

    fun secondary(r: MeasureResult): Quantity? = r.secondary?.let {
        when (r.mode) {
            MeasureMode.AREA -> lengthFromMetres(it)
            MeasureMode.VOLUME -> area(it)
            MeasureMode.DISTANCE, MeasureMode.HEIGHT -> null
        }
    }

    private fun fmt(v: Double, digits: Int) = String.format(locale, "%.${digits}f", v)

    private companion object {
        val Typed = Regex("""\d+(\.\d*)?|\.\d+""")
        const val MM_PER_FT = 304.8

        const val MAX_TYPED_MM = 1_000_000
    }
}
