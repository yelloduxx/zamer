package dev.bezh.zamer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.FileProvider
import dev.bezh.zamer.domain.Background
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** The longest side a photo is kept at; enough to zoom into, small enough to draw over smoothly. */
private const val MAX_SIDE = 2560

/** The photos sketches are drawn over, kept in the app's files as upright JPEGs. */
class PhotoStore(private val context: Context) {
    private val dir get() = File(context.filesDir, "photos")
    private val cameraShot get() = File(context.cacheDir, "camera/shot.jpg")

    /** Where the camera app writes the next shot, as a URI it is allowed to write. */
    fun newCameraShot(): Uri {
        val file = cameraShot
        file.parentFile?.mkdirs()
        file.delete()
        return context.shareable(file)
    }

    /**
     * Copies the image at [uri] into the photos folder, turned upright as its EXIF says and scaled down to [MAX_SIDE],
     * and describes it. A shot from [newCameraShot] is removed from the cache once copied.
     */
    suspend fun import(uri: Uri): Background.Photo = withContext(Dispatchers.IO) {
        try {
            // ImageDecoder applies the EXIF orientation, and reports and takes sizes the right way up.
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val k = minOf(1f, MAX_SIDE.toFloat() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * k).roundToInt(), (info.size.height * k).roundToInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val name = "${UUID.randomUUID()}.jpg"
            dir.mkdirs()
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            Background.Photo(name, bitmap.width, bitmap.height).also { bitmap.recycle() }
        } finally {
            cameraShot.delete()
        }
    }

    /**
     * Photo [name] scaled down to at most [maxSide] on its long side, or null if it is gone or unreadable. A [software]
     * bitmap can be drawn into another bitmap, as an export does.
     */
    suspend fun load(name: String, maxSide: Int = MAX_SIDE, software: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(dir, name)
        if (!file.exists()) return@withContext null
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val k = minOf(1f, maxSide.toFloat() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * k).roundToInt(), (info.size.height * k).roundToInt())
                if (software) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    fun delete(name: String) {
        File(dir, name).delete()
    }
}

/** A content URI for [file] in the app's cache that another app can be granted. */
fun Context.shareable(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)
