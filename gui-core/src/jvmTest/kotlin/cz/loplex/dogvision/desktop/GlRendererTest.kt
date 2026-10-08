package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.rgb
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The renderer, in the context the window opens on this system, mirrors a frame as it is shown,
 * mirrors the frame shown again as the controls' choice changes, draws nothing once it is cleared,
 * and reads back the images it composed.\
 * A frame red on the left and blue on the right fills the area, so that the area's ends show which way it is turned.
 */
class GlRendererTest {
    private val pictures = LinkedBlockingQueue<Picture<ByteArray>>()
    private val failures = LinkedBlockingQueue<String>()
    private val renderer = GlRenderer(null, { pixels, _, _ -> pixels }, pictures::put, failures::put)

    private val frame = frameOf(
        Image(WIDTH, HEIGHT, IntArray(WIDTH * HEIGHT) { if (it % WIDTH < WIDTH / 2) RED else BLUE }),
    )

    @AfterTest
    fun close() = renderer.close()

    /** The colours at the left and the right end of the middle row of the next picture, "red" or "blue". */
    private fun ends(): Pair<String, String> {
        val picture = assertNotNull(pictures.poll(WAIT_SECONDS, TimeUnit.SECONDS), "no picture: ${failures.peek()}")

        fun at(x: Int): String {
            val offset = (HEIGHT / 2 * WIDTH + x) * 4
            val red = picture.image[offset].toInt() and 0xFF
            val blue = picture.image[offset + 2].toInt() and 0xFF
            return if (red > blue) "red" else "blue"
        }
        return at(1) to at(WIDTH - 2)
    }

    private fun start() {
        renderer.setView(View(Params(Species.HUMAN), sideBySide = false))
        renderer.setArea(Area(WIDTH, HEIGHT, captionHeight = 0, gap = 0))
    }

    @Test
    fun aFrameShownMirroredIsMirroredAndTurnedBackAsTheChoiceChanges() {
        start()
        renderer.show(frame, live = true, mirrored = true)
        assertEquals("blue" to "red", ends(), "shown mirrored")
        renderer.setMirrored(false)
        assertEquals("red" to "blue", ends(), "turned back")
        renderer.show(frame, live = true, mirrored = true)
        assertEquals("blue" to "red", ends(), "the next frame shown mirrored")
    }

    @Test
    fun nothingIsDrawnOnceClearedUntilAnotherFrameIsShown() {
        start()
        renderer.show(frame, live = false)
        assertEquals("red" to "blue", ends())
        renderer.clear()
        renderer.setArea(Area(WIDTH, HEIGHT, captionHeight = 0, gap = 1))
        renderer.setMirrored(true)
        assertNull(pictures.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS), "a picture after the frame was cleared")
        renderer.show(frame, live = false)
        assertEquals("red" to "blue", ends(), "the frame shown after")
    }

    @Test
    fun theImagesComposedAreReadBackAndNoneBeforeAFrame() {
        start()
        val answers = LinkedBlockingQueue<List<List<Image>?>>()
        renderer.readImages { answers.put(listOf(it)) }
        assertNull(assertNotNull(answers.poll(WAIT_SECONDS, TimeUnit.SECONDS)).single(), "images before a frame")
        renderer.setView(View(Params(Species.HUMAN), sideBySide = true))
        renderer.show(frame, live = false)
        ends()
        renderer.readImages { answers.put(listOf(it)) }
        val images = assertNotNull(assertNotNull(answers.poll(WAIT_SECONDS, TimeUnit.SECONDS)).single())
        assertEquals(List(2) { WIDTH to HEIGHT }, images.map { it.width to it.height })
        for (image in images) {
            val row = HEIGHT / 2 * WIDTH
            assertEquals(RED, image.pixels[row + 1], "left")
            assertEquals(BLUE, image.pixels[row + WIDTH - 2], "right")
        }
    }

    private companion object {
        const val WIDTH = 40
        const val HEIGHT = 30
        const val WAIT_SECONDS = 10L
        const val QUIET_MILLIS = 300L
        val RED = rgb(255, 0, 0)
        val BLUE = rgb(0, 0, 255)
    }
}
