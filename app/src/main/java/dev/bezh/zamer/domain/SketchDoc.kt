package dev.bezh.zamer.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Background {
    @Serializable
    @SerialName("plan")
    data object Plan : Background

    @Serializable
    @SerialName("photo")
    data class Photo(val file: String, val width: Int, val height: Int) : Background
}

@Serializable
data class SketchDoc(
    val id: Long,
    val created: Long,
    val name: String? = null,
    val background: Background = Background.Plan,
    val sketch: Sketch = Sketch(),
) {
    val grid: Int? get() = if (background == Background.Plan) GRID_MM else null

    fun put(mm: Int) = copy(sketch = sketch.put(mm, toScale = background == Background.Plan))

    fun renamed(title: String) = copy(name = title.trim().ifEmpty { null })
}
