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

sealed interface MeasureResult {
    val mode: MeasureMode
    val valuesMm: List<Int>

    data class Distance(val lengthMm: Int) : MeasureResult {
        override val mode get() = MeasureMode.DISTANCE
        override val valuesMm get() = listOf(lengthMm)
        val metres get() = lengthMm / 1000.0
    }

    data class Area(val lengthMm: Int, val widthMm: Int) : MeasureResult {
        override val mode get() = MeasureMode.AREA
        override val valuesMm get() = listOf(lengthMm, widthMm)
        val squareMetres get() = lengthMm / 1000.0 * (widthMm / 1000.0)
        val perimeterMetres get() = 2 * (lengthMm / 1000.0 + widthMm / 1000.0)
    }

    data class Volume(val lengthMm: Int, val widthMm: Int, val heightMm: Int) : MeasureResult {
        override val mode get() = MeasureMode.VOLUME
        override val valuesMm get() = listOf(lengthMm, widthMm, heightMm)
        val cubicMetres get() = lengthMm / 1000.0 * (widthMm / 1000.0) * (heightMm / 1000.0)
        val floorSquareMetres get() = lengthMm / 1000.0 * (widthMm / 1000.0)
    }

    @ConsistentCopyVisibility
    data class Height private constructor(val baseMm: Int, val hypotenuseMm: Int) : MeasureResult {
        override val mode get() = MeasureMode.HEIGHT
        override val valuesMm get() = listOf(baseMm, hypotenuseMm)
        val metres: Double
            get() {
                val base = baseMm / 1000.0
                val hypotenuse = hypotenuseMm / 1000.0
                return sqrt(hypotenuse * hypotenuse - base * base)
            }

        companion object {
            fun of(baseMm: Int, hypotenuseMm: Int): Height? = if (hypotenuseMm > baseMm) Height(baseMm, hypotenuseMm) else null
        }
    }

    companion object {
        fun of(mode: MeasureMode, valuesMm: List<Int>): MeasureResult? {
            if (valuesMm.size != mode.sides.size) return null
            return when (mode) {
                MeasureMode.DISTANCE -> Distance(valuesMm[0])
                MeasureMode.AREA -> Area(valuesMm[0], valuesMm[1])
                MeasureMode.VOLUME -> Volume(valuesMm[0], valuesMm[1], valuesMm[2])
                MeasureMode.HEIGHT -> Height.of(valuesMm[0], valuesMm[1])
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

    fun primary(r: MeasureResult): Quantity = when (r) {
        is MeasureResult.Distance -> lengthFromMetres(r.metres)
        is MeasureResult.Area -> area(r.squareMetres)
        is MeasureResult.Volume -> volume(r.cubicMetres)
        is MeasureResult.Height -> lengthFromMetres(r.metres)
    }

    private fun fmt(v: Double, digits: Int) = String.format(locale, "%.${digits}f", v)

    private companion object {
        val Typed = Regex("""\d+(\.\d*)?|\.\d+""")
        const val MM_PER_FT = 304.8

        const val MAX_TYPED_MM = 1_000_000
    }
}
