package cz.loplex.dogvision.render

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.blue
import cz.loplex.dogvision.core.green
import cz.loplex.dogvision.core.red
import cz.loplex.dogvision.core.rgb
import cz.loplex.dogvision.video.RECORDING_FPS
import cz.loplex.dogvision.video.Recorder
import cz.loplex.dogvision.video.Written
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The renderer records the images it draws, at the recording's rate, into an .mp4 of the view's
 * size, in an offscreen context whose configuration takes an encoder's surface as the screen's does.
 * The frame is four flat quadrants, so that the flat middle of each is compared, away from the edges
 * the encoder blurs.
 */
@RunWith(AndroidJUnit4::class)
class RecordingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var gl: OffscreenContext
    private val frames = FrameExchange()
    private val renderer = ViewRenderer(frames) { }
    private val file = File(context.cacheDir, "recording.mp4")
    private val finished = CountDownLatch(1)
    private var result: Result<Written>? = null
    private val recorder = Recorder(file) {
        result = it
        finished.countDown()
    }

    @Before
    fun makeContext() {
        file.delete()
        gl = OffscreenContext(recordable = true)
        renderer.onSurfaceCreated(null, null)
        // Wide enough that the layout puts the images side by side.
        renderer.onSurfaceChanged(null, 1024, 256)
    }

    @After
    fun releaseContext() = gl.release()

    private fun publishQuadrants() {
        val image = Image(
            WIDTH,
            HEIGHT,
            IntArray(WIDTH * HEIGHT) {
                val right = it % WIDTH >= WIDTH / 2
                val bottom = it / WIDTH >= HEIGHT / 2
                QUADRANTS[(if (bottom) 2 else 0) + if (right) 1 else 0]
            },
        )
        val frame = Frame.allocate(WIDTH, HEIGHT)
        for (pixel in image.pixels) {
            frame.pixels.put(red(pixel).toByte()).put(green(pixel).toByte()).put(blue(pixel).toByte()).put(-1)
        }
        frames.publish(frame, frames.open())
    }

    private fun finish(): Result<Written> {
        assertTrue("the recording did not finish", finished.await(10, TimeUnit.SECONDS))
        return result!!
    }

    @Test
    fun theViewIsRecordedAtItsSizeAndRate() {
        publishQuadrants()
        renderer.view = View(Params(Species.DOG))
        renderer.recorder = recorder
        repeat(DRAWS) {
            renderer.onDrawFrame(null)
            Thread.sleep(40)
        }
        val images = renderer.readImages()
        renderer.recorder = null
        renderer.onDrawFrame(null)
        recorder.stop()
        val written = finish().getOrThrow()
        assertTrue(written.silent)

        val extractor = MediaExtractor().apply { setDataSource(file.path) }
        assertEquals("tracks", 1, extractor.trackCount)
        val format = extractor.getTrackFormat(0)
        val size = format.getInteger(MediaFormat.KEY_WIDTH) to format.getInteger(MediaFormat.KEY_HEIGHT)
        assertEquals(written.scaledTo ?: (2 * WIDTH to HEIGHT), size)
        extractor.selectTrack(0)
        val times = mutableListOf<Long>()
        do times += extractor.sampleTime while (extractor.advance())
        extractor.release()
        // Every draw falls on a point of the grid of its own, 40 ms apart where the grid's are 33.
        assertEquals(DRAWS, times.size)
        for (time in times) {
            val frame = (time * RECORDING_FPS + 500_000) / 1_000_000
            assertTrue("$time µs is off the grid", abs(time - frame * 1_000_000 / RECORDING_FPS) <= 1)
        }

        val first = MediaMetadataRetriever().run {
            setDataSource(file.path)
            getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST).also { release() }
        }!!
        images.forEachIndexed { i, image ->
            for ((x, y) in listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3)) {
                val e = image[WIDTH * x / 4, HEIGHT * y / 4]
                val a = first.getPixel(
                    (i * WIDTH + WIDTH * x / 4) * first.width / (2 * WIDTH),
                    HEIGHT * y / 4 * first.height / HEIGHT,
                )
                val worst = listOf(::red, ::green, ::blue).maxOf { abs(it(e) - it(a)) }
                assertTrue(
                    "image $i ($x, $y): ${Integer.toHexString(a)} for ${Integer.toHexString(e)}",
                    worst <= TOLERANCE,
                )
            }
        }
    }

    @Test
    fun aRecordingStoppedBeforeAnyFrameFails() {
        recorder.stop()
        assertTrue(finish().isFailure)
        assertFalse(file.exists())
    }

    private companion object {
        const val WIDTH = 256
        const val HEIGHT = 144
        const val DRAWS = 8
        val QUADRANTS = listOf(rgb(200, 40, 40), rgb(40, 200, 40), rgb(40, 40, 200), rgb(128, 128, 128))

        /** What 8-bit 4:2:0 YUV costs a flat colour, once. */
        const val TOLERANCE = 6
    }
}
