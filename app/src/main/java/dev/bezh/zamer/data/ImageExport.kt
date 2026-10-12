package dev.bezh.zamer.data

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A file name made of [title], keeping letters, digits, spaces, dots, dashes and underscores. */
fun imageName(title: String) = "Zamer " + title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim()

/** Saves [bitmap] as a PNG named [name] in Pictures/Zamer, which needs no permission; false if it couldn't. */
suspend fun saveToGallery(context: Context, bitmap: Bitmap, name: String): Boolean = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Zamer")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: return@withContext false
    try {
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: throw IOException("no stream")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    } catch (e: IOException) {
        resolver.delete(uri, null, null)
        false
    }
}

/** Writes [bitmap] as a PNG named [name] to the cache and offers it to the share sheet; false if it couldn't. */
suspend fun shareImage(context: Context, bitmap: Bitmap, name: String): Boolean {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { deleteRecursively() }
        dir.mkdirs()
        try {
            File(dir, "$name.png").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        } catch (e: IOException) {
            null
        }
    } ?: return false
    val uri = context.shareable(file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}
