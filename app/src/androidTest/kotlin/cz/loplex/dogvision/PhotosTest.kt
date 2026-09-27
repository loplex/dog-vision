package cz.loplex.dogvision

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * A photo reaches the GPU with its colours as they are stored, whatever their alpha, as the web page and core's
 * pipeline for a photo saved at full size read them.
 */
@RunWith(AndroidJUnit4::class)
class PhotosTest {
    @Test
    fun aSemiTransparentPixelKeepsItsColour() {
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.argb(128, 200, 100, 50))
        bitmap.setPixel(1, 0, Color.argb(255, 10, 20, 30))
        val frame = bitmap.toFrame(0 to false)
        val actual = List(8) { frame.pixels.get(it).toInt() and 0xFF }
        val expected = listOf(200, 100, 50, 128, 10, 20, 30, 255)
        // A bitmap keeps its colours multiplied by alpha, which rounds a colour at half alpha to every other step.
        assertTrue("RGBA $actual, not $expected", expected.zip(actual).all { (e, a) -> abs(e - a) <= 2 })
    }
}
