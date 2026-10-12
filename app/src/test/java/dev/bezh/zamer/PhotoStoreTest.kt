package dev.bezh.zamer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.bezh.zamer.data.PhotoStore
import dev.bezh.zamer.domain.Background
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val photos = PhotoStore(context)

    @Test
    fun aPhotoIsStoredUprightAndNoLongerThanTheLimit() = runBlocking {
        val sideways = jpeg(6000, 3000, exifOrientation = 6)
        val photo = photos.import(Uri.fromFile(sideways))
        assertEquals("turned a quarter as EXIF says, then scaled down", Background.Photo(photo.file, 1280, 2560), photo)
        val stored = photos.load(photo.file, software = true)!!
        assertEquals(1280 to 2560, stored.width to stored.height)

        val small = photos.import(Uri.fromFile(jpeg(800, 600, exifOrientation = 1)))
        assertEquals("a small photo keeps its size", 800 to 600, small.width to small.height)
    }

    @Test
    fun aDeletedPhotoIsGone() = runBlocking {
        val photo = photos.import(Uri.fromFile(jpeg(100, 50, exifOrientation = 1)))
        val file = File(context.filesDir, "photos/${photo.file}")
        assertTrue(file.exists())
        photos.delete(photo.file)
        assertFalse(file.exists())
        assertEquals(null, photos.load(photo.file))
    }

    private fun jpeg(width: Int, height: Int, exifOrientation: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val plain = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // big-endian TIFF header, first IFD at 8
            0x00, 0x01, // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, exifOrientation.toByte(), 0x00, 0x00, // Orientation, SHORT
            0x00, 0x00, 0x00, 0x00, // no next IFD
        )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        val withExif = plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
        return File.createTempFile("photo", ".jpg").apply { writeBytes(withExif) }
    }
}
