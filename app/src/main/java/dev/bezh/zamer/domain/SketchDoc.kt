package dev.bezh.zamer.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a sketch is drawn over, which decides what its coordinates mean. */
@Serializable
sealed interface Background {
    /** A floor plan to scale: coordinates are millimetres, over a dot grid. */
    @Serializable
    @SerialName("plan")
    data object Plan : Background

    /**
     * A photo saved as [file] in the app's photos folder, [width] by [height] pixels. Coordinates are the photo's
     * pixels, so nothing is to scale and a measured side is only labelled.
     */
    @Serializable
    @SerialName("photo")
    data class Photo(val file: String, val width: Int, val height: Int) : Background
}

/** One of the saved sketches; [created] is in epoch milliseconds and [name] is what the user called it, if anything. */
@Serializable
data class SketchDoc(
    val id: Long,
    val created: Long,
    val name: String? = null,
    val background: Background = Background.Plan,
    val sketch: Sketch = Sketch(),
) {
    /** Millimetres between grid dots on a plan; a photo has no grid. */
    val grid: Int? get() = if (background == Background.Plan) GRID_MM else null

    /** Measures the target side: a plan redraws it to [mm], a photo only labels it. */
    fun put(mm: Int) = copy(sketch = sketch.put(mm, toScale = background == Background.Plan))

    /** A blank [title] removes the name. */
    fun renamed(title: String) = copy(name = title.trim().ifEmpty { null })
}
