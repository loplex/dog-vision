package cz.loplex.dogvision.video

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.loplex.dogvision.core.blue
import cz.loplex.dogvision.core.green
import cz.loplex.dogvision.core.red
import cz.loplex.dogvision.core.rgb
import cz.loplex.dogvision.render.Frame
import cz.loplex.dogvision.render.FrameExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * A video's frames reach the renderer upright, at their colours and scaled down to the size asked
 * for. The videos are tools/make_test_videos.sh's: four quadrants of one colour each.
 */
@RunWith(AndroidJUnit4::class)
class VideoFeedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The quadrants' colours, as rows of the frame upright: top left, top right, bottom left, bottom right. */
    private data class Quadrants(val width: Int, val height: Int, val colours: List<Int>)

    /** The first frame [asset] delivers, reduced to its size and the colour at each quadrant's centre. */
    private fun firstFrame(asset: String, longestSide: Int): Quadrants {
        val file = File(instrumentation.targetContext.cacheDir, asset)
        instrumentation.context.assets.open(asset).use { input -> file.outputStream().use(input::copyTo) }
        val frames = FrameExchange()
        val delivered = CountDownLatch(1)
        var quadrants: Quadrants? = null
        frames.onPublish = {
            frames.take()?.let {
                if (quadrants == null) quadrants = quadrantsOf(it)
                delivered.countDown()
            }
        }
        var error: Throwable? = null
        lateinit var feed: VideoFeed
        instrumentation.runOnMainSync {
            feed = VideoFeed(instrumentation.targetContext, Uri.fromFile(file), frames, longestSide) {
                error = it
                delivered.countDown()
            }
            feed.play()
        }
        try {
            assertTrue("no frame came", delivered.await(10, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync(feed::release)
        }
        assertNull(error?.stackTraceToString(), error)
        return quadrants!!
    }

    private fun quadrantsOf(frame: Frame): Quadrants {
        val colours = listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3).map { (x, y) ->
            frame.uprightPixel(frame.uprightWidth * x / 4, frame.uprightHeight * y / 4)
        }
        return Quadrants(frame.uprightWidth, frame.uprightHeight, colours)
    }

    private fun assertColours(expected: List<Int>, actual: List<Int>) {
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            val worst = listOf(::red, ::green, ::blue).maxOf { abs(it(e) - it(a)) }
            assertTrue("quadrant $i: ${Integer.toHexString(a)} for ${Integer.toHexString(e)}", worst <= TOLERANCE)
        }
    }

    @Test
    fun aVideoKeepsItsSizeAndColours() {
        val shown = firstFrame("quadrants.mp4", longestSide = 1280)
        assertEquals(256 to 144, shown.width to shown.height)
        assertColours(listOf(RED, GREEN, BLUE, GREY), shown.colours)
    }

    @Test
    fun aVideoRecordedUprightIsTurnedUpright() {
        val shown = firstFrame("quadrants-turned.mp4", longestSide = 1280)
        assertEquals(144 to 256, shown.width to shown.height)
        assertColours(listOf(BLUE, RED, GREY, GREEN), shown.colours)
    }

    @Test
    fun aLargeVideoIsScaledDown() {
        val shown = firstFrame("quadrants.mp4", longestSide = 128)
        assertEquals(128 to 72, shown.width to shown.height)
        assertColours(listOf(RED, GREEN, BLUE, GREY), shown.colours)
    }

    @Test
    fun aVideoTheFirstDecoderRefusesIsPlayedByTheNext() {
        // Qualcomm's hardware decoder refuses 96 x 64, and a phone with it plays the video through another. The
        // colours are not checked: Android's software decoder, which takes over, hands frames over that come out
        // as BT.601 decodes them, as the emulator's decoder does; the other tests hold the colours.
        val shown = firstFrame("quadrants-small.mp4", longestSide = 1280)
        assertEquals(96 to 64, shown.width to shown.height)
    }

    @Test
    fun anHdrVideoIsShownUprightAtItsSize() {
        // A GPU with GL_EXT_YUV_target tone-maps the HLG video, and one without, as the emulator's, shows it as
        // decoded; either way its colours are not the ones stored, and only the reddest and the bluest quadrant
        // are checked.
        val shown = firstFrame("quadrants-hlg.mp4", longestSide = 128)
        assertEquals(128 to 72, shown.width to shown.height)
        val (topLeft, _, bottomLeft) = shown.colours
        assertTrue("top left ${Integer.toHexString(topLeft)}", red(topLeft) > maxOf(green(topLeft), blue(topLeft)))
        assertTrue(
            "bottom left ${Integer.toHexString(bottomLeft)}",
            blue(bottomLeft) > maxOf(red(bottomLeft), green(bottomLeft)),
        )
    }

    private companion object {
        val RED = rgb(200, 40, 40)
        val GREEN = rgb(40, 200, 40)
        val BLUE = rgb(40, 40, 200)
        val GREY = rgb(128, 128, 128)

        /** What 8-bit 4:2:0 YUV and its conversion back to RGB cost a flat colour. */
        const val TOLERANCE = 6
    }
}
