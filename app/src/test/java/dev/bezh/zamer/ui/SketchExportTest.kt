package dev.bezh.zamer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import dev.bezh.zamer.domain.Background
import dev.bezh.zamer.domain.Formatter
import dev.bezh.zamer.domain.Pt
import dev.bezh.zamer.domain.Rect
import dev.bezh.zamer.domain.Segment
import dev.bezh.zamer.domain.Sketch
import dev.bezh.zamer.domain.SketchDoc
import dev.bezh.zamer.domain.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SketchExportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1f), LayoutDirection.Ltr)
    private val style = TextStyle(fontSize = 14.sp)
    private val formatter = Formatter(UnitSystem.METRIC, 2)
    private val overPhoto = photoColors(lightColorScheme())

    private fun render(doc: SketchDoc, photo: Bitmap? = null, caption: String? = null) =
        renderSketch(doc, photo, caption, formatter, measurer, style, overPhoto)

    @Test
    fun aPlanIsCroppedToItsShapesWithItsLongSideAtTheCap() {
        val room = Sketch().add(Rect(1, Pt(0, 0), Pt(4000, 3000))).put(4000).put(3000).add(Segment(2, Pt(4000, 0), Pt(4300, 0))).put(300)
        val image = render(SketchDoc(1, 1, "Kitchen", sketch = room), caption = "Kitchen · 12.00 m²")!!
        save(image, "export-plan")
        // 4300 by 3000 mm, 56 dp of margin round it at 6.4 px per dp.
        val margin = 56 * 4096 / 640f
        assertEquals(4096, image.width)
        assertEquals((3000 * (4096 - 2 * margin) / 4300 + 2 * margin).toInt(), image.height)
        assertEquals("paper behind the drawing", Color.WHITE, image.getPixel(10, 10))
        assertTrue("the left wall is drawn dark", Color.red(image.getPixel(margin.toInt() + 1, image.height / 2 + 300)) < 100)
    }

    @Test
    fun aPhotoIsExportedWholeAtItsOwnSize() {
        val photo = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 120, 200)) }
        val sketch = Sketch().add(Segment(1, Pt(100, 600), Pt(1500, 600))).put(3000, toScale = false)
        val image = render(SketchDoc(1, 1, background = Background.Photo("p.jpg", 1600, 1200), sketch = sketch), photo)!!
        save(image, "export-photo")
        assertEquals(1600 to 1200, image.width to image.height)
        assertEquals("the photo is behind the drawing", Color.rgb(40, 120, 200), image.getPixel(5, 5))
        assertEquals("the line is over it", Color.WHITE, image.getPixel(300, 600))
    }

    @Test
    fun thereIsNothingToExportWithoutShapesOrPhoto() {
        assertNull(render(SketchDoc(1, 1)))
        assertNull(render(SketchDoc(1, 1, background = Background.Photo("p.jpg", 10, 10))))
    }

    private fun save(image: Bitmap, name: String) {
        File("build/outputs/roborazzi").mkdirs()
        File("build/outputs/roborazzi/$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
