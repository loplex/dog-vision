package cz.loplex.dogvision.cli

import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.rgb
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhotosTest {
    @TempDir
    lateinit var directory: File

    /** A 3 x 2 image whose pixels are all different: 0 1 2 over 3 4 5. */
    private val image = Image(3, 2, IntArray(6) { rgb(it * 40, 0, 0) })

    private fun pixels(vararg indices: Int) = IntArray(indices.size) { image.pixels[indices[it]] }

    @Test
    fun aTurnTurnsClockwiseThenMirrors() {
        val cases = mapOf(
            (90 to false) to (2 to pixels(3, 0, 4, 1, 5, 2)),
            (180 to false) to (3 to pixels(5, 4, 3, 2, 1, 0)),
            (270 to false) to (2 to pixels(2, 5, 1, 4, 0, 3)),
            (0 to true) to (3 to pixels(2, 1, 0, 5, 4, 3)),
            (90 to true) to (2 to pixels(0, 3, 1, 4, 2, 5)), // transposed
            (270 to true) to (2 to pixels(5, 2, 4, 1, 3, 0)), // transversed
        )
        for ((turn, expected) in cases) {
            val turned = turned(image, turn.first, turn.second)
            assertEquals(expected.first, turned.width, turn.toString())
            assertContentEquals(expected.second, turned.pixels, turn.toString())
        }
    }

    @Test
    fun theExifOrientationIsReadInEitherByteOrder() {
        for (orientation in 1..8) {
            for (bigEndian in listOf(true, false)) {
                assertEquals(orientation, exifOrientation(jpeg(orientation, bigEndian)), "$orientation $bigEndian")
            }
        }
    }

    @Test
    fun aPhotoWithoutExifIsUpright() {
        assertEquals(1, exifOrientation(jpeg(orientation = null)))
        assertEquals(1, exifOrientation(pngBytes(image)))
        assertEquals(1, exifOrientation(ByteArray(0)))
        // Cut off in the middle of the EXIF segment.
        val cut = jpeg(6, bigEndian = true)
        assertEquals(1, exifOrientation(cut.copyOf(30)))
    }

    @Test
    fun aPhotoIsReadTurnedAsItsExifSays() {
        val file = File(directory, "turned.jpg").apply { writeBytes(jpeg(6, bigEndian = false, width = 4, height = 2)) }
        val photo = readPhoto(file)!!
        assertEquals(2 to 4, photo.width to photo.height)
    }

    @Test
    fun aPngIsReadExactly() {
        val file = File(directory, "image.png").apply { writeBytes(pngBytes(image)) }
        assertContentEquals(image.pixels, readPhoto(file)!!.pixels)
    }

    @Test
    fun whatIsNoImageReadsAsNull() {
        val file = File(directory, "text.jpg").apply { writeText("not an image") }
        assertNull(readPhoto(file))
    }

    companion object {
        fun pngBytes(image: Image): ByteArray {
            val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
            buffered.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
            return ByteArrayOutputStream().also { ImageIO.write(buffered, "png", it) }.toByteArray()
        }

        /** A black JPEG of [width] x [height], with an EXIF segment giving [orientation] after its start if given. */
        fun jpeg(orientation: Int?, bigEndian: Boolean = true, width: Int = 8, height: Int = 8): ByteArray {
            val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val encoded = ByteArrayOutputStream().also { ImageIO.write(buffered, "jpg", it) }.toByteArray()
            if (orientation == null) return encoded
            fun u16(value: Int) = if (bigEndian) listOf(value shr 8, value) else listOf(value, value shr 8)
            fun u32(value: Int) = if (bigEndian) u16(value shr 16) + u16(value) else u16(value) + u16(value shr 16)
            val tiff = (if (bigEndian) listOf(0x4D, 0x4D) else listOf(0x49, 0x49)) + u16(42) + u32(8) +
                u16(1) + u16(0x0112) + u16(3) + u32(1) + u16(orientation) + u16(0) + u32(0)
            val exif = listOf(0x45, 0x78, 0x69, 0x66, 0, 0) + tiff
            val segment = listOf(0xFF, 0xE1) + listOf((exif.size + 2) shr 8, exif.size + 2) + exif
            val bytes = segment.map { it.toByte() }.toByteArray()
            return encoded.copyOfRange(0, 2) + bytes + encoded.copyOfRange(2, encoded.size)
        }
    }
}
