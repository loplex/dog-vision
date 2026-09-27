package cz.loplex.dogvision.video

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.loplex.dogvision.BitmapSink
import cz.loplex.dogvision.BitmapSource
import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.blue
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.core.green
import cz.loplex.dogvision.core.meanLinearRgb
import cz.loplex.dogvision.core.red
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * A converted video is the view of every frame, as core renders it, with the original's sound. The
 * video is tools/make_test_videos.sh's quadrants.mp4, whose frames are four flat colours, so that
 * the flat middle of each quadrant is compared, away from the edges the encoders blur.
 */
@RunWith(AndroidJUnit4::class)
class ConversionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun asset(name: String): File {
        val file = File(context.cacheDir, name)
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
        return file
    }

    private fun firstFrame(file: File): Bitmap = MediaMetadataRetriever().run {
        setDataSource(file.path)
        val frame = getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
        release()
        checkNotNull(frame) { "no frame in $file" }
    }

    /** The mime types of the tracks of [file], with the size of its video track. */
    private fun tracks(file: File): Pair<List<String>, Pair<Int, Int>> {
        val extractor = MediaExtractor().apply { setDataSource(file.path) }
        val formats = List(extractor.trackCount, extractor::getTrackFormat)
        extractor.release()
        val video = formats.single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        val size = video.getInteger(MediaFormat.KEY_WIDTH) to video.getInteger(MediaFormat.KEY_HEIGHT)
        return formats.map { it.getString(MediaFormat.KEY_MIME)!! } to size
    }

    /** Converts quadrants.mp4 as [view] shows it, and returns how it was written. */
    private fun check(view: View, textureLimit: Int = Int.MAX_VALUE): Written {
        val input = asset("quadrants.mp4")
        val output = File(context.cacheDir, "converted.mp4").apply { delete() }
        var progressed = 0.0
        val written = runBlocking {
            convertVideo(context, Uri.fromFile(input), view, output, textureLimit) { progressed = it }
        }
        assertTrue("progress $progressed", progressed in 0.0..1.0)
        assertTrue("sound", written.sound)
        val source = firstFrame(input)
        val (width, height) = composedSize(view, source.width, source.height)
        val (mimeTypes, size) = tracks(output)
        assertTrue(mimeTypes.toString(), mimeTypes.any { it.startsWith("audio/") })
        // An encoder that cannot take the view's size is given a smaller one of the same shape.
        assertEquals(written.scaledTo ?: (width to height), size)
        assertEquals(width.toDouble() / height, size.first.toDouble() / size.second, 0.05)
        val expected = createBitmap(width, height)
        compose(BitmapSource(source), view, BitmapSink(expected), {
            meanLinearRgb(source.width, source.height, source::getPixel)
        })
        val actual = firstFrame(output)
        for (image in 0 until view.images) {
            for ((x, y) in listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3)) {
                val px = image * source.width + source.width * x / 4
                val py = source.height * y / 4
                val e = expected.getPixel(px, py)
                val a = actual.getPixel(px * actual.width / width, py * actual.height / height)
                val worst = listOf(::red, ::green, ::blue).maxOf { abs(it(e) - it(a)) }
                assertTrue(
                    "image $image at ($px, $py): ${Integer.toHexString(a)} for ${Integer.toHexString(e)}",
                    worst <= TOLERANCE,
                )
            }
        }
        return written
    }

    @Test
    fun theOriginalBesideTheSimulation() {
        check(View(Params(Species.DOG)))
    }

    @Test
    fun anotherSpeciesAndTheMapOfDifferences() {
        check(View(Params(Species.DOG), compare = Species.HUMAN, difference = true, arrangement = Arrangement.ROW))
    }

    @Test
    fun aViewLargerThanTheGpusTexturesIsScaledDownToFit() {
        // Two images of 256 x 144 side by side, 512 wide, where a texture may be 480 at most. The view scaled down,
        // 480 x 134, is one that encoders take as it is, so that it is the effect that scales it: Qualcomm's HEVC
        // encoder takes no side under 128, and Android's software one, as the emulator has, none over 512. A view
        // taller than wide would be encoded on its side.
        val (width, height) = checkNotNull(check(View(Params(Species.DOG)), textureLimit = 480).scaledTo) {
            "not scaled"
        }
        assertTrue("$width x $height", width <= 480 && height <= 480)
    }

    @Test
    fun aVideoTheFirstDecoderRefusesIsConverted() {
        // Qualcomm's hardware decoder refuses 96 x 64, and a phone with it decodes the video with another. Its size
        // is not held to the view's, which is under the smallest that some encoders take, and is scaled up for them.
        val input = asset("quadrants-small.mp4")
        val output = File(context.cacheDir, "converted-small.mp4").apply { delete() }
        val written = runBlocking { convertVideo(context, Uri.fromFile(input), View(Params(Species.DOG)), output) {} }
        assertTrue("sound", written.sound)
        val (mimeTypes, _) = tracks(output)
        assertTrue(mimeTypes.toString(), mimeTypes.any { it.startsWith("video/") })
    }

    private companion object {
        /** What two rounds of 8-bit 4:2:0 YUV cost a flat colour. */
        const val TOLERANCE = 8
    }
}
