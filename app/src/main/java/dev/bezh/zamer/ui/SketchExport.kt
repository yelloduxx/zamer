package dev.bezh.zamer.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.bezh.zamer.domain.Background
import dev.bezh.zamer.domain.Formatter
import dev.bezh.zamer.domain.SketchDoc
import kotlin.math.roundToInt

internal const val MAX_EXPORT_PX = 4096

internal const val EXPORT_LONG_SIDE_DP = 640f

internal val PlanMargin = 56.dp

private val Paper = Color.White
private val PaperColors = SketchColors(
    line = Color(0xFF231918),
    raised = Color(0xFFB3261E),
    label = Color(0xFFF7E4E1),
    onLabel = Color(0xFF231918),
    selected = Color(0xFFB3261E),
    onSelected = Color.White,
    area = Color(0xFFFFDAD5),
    onArea = Color(0xFF410002),
)

internal fun renderSketch(
    doc: SketchDoc,
    photo: Bitmap?,
    caption: String?,
    formatter: Formatter,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    photoColors: SketchColors,
): Bitmap? {
    val labels = SketchLabels(measurer, labelStyle, formatter)
    val width: Int
    val height: Int
    val density: Density
    val view: SketchView
    when (val background = doc.background) {
        Background.Plan -> {
            density = Density(MAX_EXPORT_PX / EXPORT_LONG_SIDE_DP)
            val margin = with(density) { PlanMargin.toPx() }
            val points = doc.sketch.shapes.flatMap { it.points }
            val boxes = with(density) { noteBoxes(doc.sketch.shapes, labels) }
            val room = MAX_EXPORT_PX - 2 * margin
            val k = fitZoom(points, boxes, room, room, 0f..Float.MAX_VALUE) ?: return null
            val reach = reach(points, boxes, k)
            width = (reach.width + 2 * margin).roundToInt()
            height = (reach.height + 2 * margin).roundToInt()
            view = SketchView(margin - reach.left, margin - reach.top, k)
        }
        is Background.Photo -> {
            if (photo == null) return null
            width = photo.width
            height = photo.height
            density = Density(maxOf(width, height) / EXPORT_LONG_SIDE_DP)
            view = SketchView(0f, 0f, width.toFloat() / background.width)
        }
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val size = Size(width.toFloat(), height.toFloat())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), size) {
        val colors = when (doc.background) {
            Background.Plan -> {
                drawRect(Paper)
                PaperColors
            }
            is Background.Photo -> {
                photo?.let { drawImage(it.asImageBitmap()) }
                photoColors
            }
        }
        drawShapes(doc.sketch.shapes, view, colors, labels)
        caption?.let {
            val text = measurer.measure(it, labelStyle.copy(color = colors.onLabel), density = this)
            val inset = 16.dp.toPx()
            val h = text.size.height + 4.dp.toPx()
            val w = text.size.width + 12.dp.toPx()
            pill(text, Offset(inset + w / 2, size.height - inset - h / 2), 0f, colors.label)
        }
    }
    return bitmap
}
