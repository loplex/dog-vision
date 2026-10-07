package cz.loplex.dogvision.common

import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.core.meanLinearRgb
import cz.loplex.dogvision.core.rgb
import cz.loplex.dogvision.texts.Str
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A photo or a video is converted as the command line and the windows convert it: named, told, cancelled. */
class ConversionTest {
    @TempDir
    lateinit var directory: File

    /** A video of [seconds] at 10 frames a second, 64 x 48, red on the left, blue on the right, without sound. */
    private fun clip(seconds: Int = 1): File {
        val file = File(directory, "clip.mp4")
        val graph = "color=c=red:s=32x48:r=10:d=$seconds[a];color=c=blue:s=32x48:r=10:d=$seconds[b];[a][b]hstack[out0]"
        val command = listOf("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", graph) +
            listOf("-c:v", "libx264", "-pix_fmt", "yuv444p", "-qp", "0", file.path)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return file
    }

    private fun photo(): File {
        val photo = Image(16, 12, IntArray(16 * 12) { rgb(it % 16 * 16, it / 16 * 20, 255 - it) })
        return File(directory, "photo.png").apply { writeBytes(PhotosTest.pngBytes(photo)) }
    }

    @Test
    fun aConvertedFileIsNamedAfterItsOriginal() {
        assertEquals(File("dir/photo.dog.png"), convertedFile(File("dir/photo.jpg"), View(), null))
        assertEquals(File("photo.dog.png"), convertedFile(File("photo.jpg"), View(), null))
        assertEquals(File("out/photo.dog.png"), convertedFile(File("dir/photo.jpg"), View(), File("out")))
        assertEquals(File("noext.dog.png"), convertedFile(File("noext"), View(), null))
    }

    @Test
    fun aConvertedFileIsNamedAfterTheSpeciesItShows() {
        val cat = View(Params(Species.CAT))
        assertEquals(File("photo.cat.png"), convertedFile(File("photo.jpg"), cat, null))
        val compared = cat.copy(sideBySide = true, compare = Species.HORSE)
        assertEquals(File("photo.horse-vs-cat.png"), convertedFile(File("photo.jpg"), compared, null))
    }

    @Test
    fun aPhotoIsWrittenWithTheShareItsMapMarks() {
        val input = photo()
        val view = View(Params(Species.CAT), sideBySide = true, difference = true)
        val original = checkNotNull(readPhoto(input))
        val (width, height) = composedSize(view, original.width, original.height)
        val share = compose(original, view, Image(width, height), { meanLinearRgb(original) })
        val output = File(directory, "photo.cat.png")
        assertEquals(Converted.Photo(output, share), Conversion(input, view, null).run())
        assertEquals(width, readPhoto(output)?.width)
    }

    @Test
    fun aVideosProgressIsToldEachTimeItsShareChanges() {
        val percents = mutableListOf<Int>()
        val converted = Conversion(clip(), View(), null).run { percents += it }
        assertEquals((1..10).map { it * 10 }, percents)
        assertIs<Converted.Video>(converted)
        assertTrue(converted.file.isFile)
    }

    @Test
    fun aCancelledVideoLeavesNoFile() {
        val conversion = Conversion(clip(), View(), null)
        assertNull(conversion.run { if (it == 30) conversion.cancel() })
        assertFalse(File(directory, "clip.dog.mp4").exists())
    }

    @Test
    fun aCancelFromAnotherThreadStopsTheVideo() {
        val conversion = Conversion(clip(seconds = 3), View(), null)
        val told = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val canceller = thread {
            told.await()
            conversion.cancel()
            cancelled.countDown()
        }
        val percents = mutableListOf<Int>()
        val converted = conversion.run { percent ->
            percents += percent
            told.countDown()
            cancelled.await()
        }
        canceller.join()
        assertNull(converted)
        assertEquals(1, percents.size, "$percents")
        assertFalse(File(directory, "clip.dog.mp4").exists())
    }

    @Test
    fun aPhotoCancelledBeforeItIsWrittenIsNot() {
        val conversion = Conversion(photo(), View(), null)
        conversion.cancel()
        assertNull(conversion.run())
        assertFalse(File(directory, "photo.dog.png").exists())
    }

    @Test
    fun whatIsNeitherAPhotoNorAVideoIsSaid() {
        val input = File(directory, "clip.mp4").apply { writeText("not a video") }
        val error = assertFailsWith<ConversionException> { Conversion(input, View(), null).run() }
        assertEquals(Str.NOT_PHOTO_OR_VIDEO, error.key)
        assertFalse(File(directory, "clip.dog.mp4").exists())
    }
}
