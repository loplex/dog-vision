package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.core.Box
import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.rgb
import cz.loplex.dogvision.testing.coreImages
import cz.loplex.dogvision.testing.pattern
import cz.loplex.dogvision.testing.worstChannelDifference
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The passes in an EGL context on the machine's GPU render what core's CPU pipeline renders, within [TOLERANCE] of 8
 * bits, as the web page's PassesTest holds WebGL 2 to it; the map of differences, whose pixels near the threshold may
 * cross it for images a step apart, is held to the share core counts. [EsPassesTest] runs them over OpenGL ES 3.0, as
 * Linux and ANGLE draw, and [DesktopGlPassesTest] over desktop OpenGL 3.3 core, as WGL draws on Windows.
 *
 * Every test runs on the one thread JUnit runs a class on, where its context is current.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PassesTest(private val api: EglContext.Api) {
    private lateinit var context: GlContext
    private lateinit var passes: Passes

    @BeforeAll
    fun makeContext() {
        context = EglContext.onDevice(api)
        println("${api.title}: ${context.renderer}, ${context.version}")
        passes = Passes(context.gl)
    }

    @AfterAll
    fun releaseContext() {
        passes.release()
        context.close()
    }

    private fun check(view: View, image: Image = pattern(96, 64)) {
        val frame = frameOf(image)
        passes.upload(frame.width, frame.height, frame.pixels)
        val actualShare = passes.compose(view)
        val (expected, share) = coreImages(image, view)
        val actual = passes.readImages(view.images)
        expected.zip(actual).take(2).forEachIndexed { i, (e, a) ->
            val worst = worstChannelDifference(e, a)
            assertTrue(worst <= TOLERANCE, "$view image $i: a channel differs by $worst")
        }
        if (share == null) {
            assertNull(actualShare)
        } else {
            assertEquals(share, assertNotNull(actualShare), 0.02)
        }
    }

    @Test
    fun theSimulationAlone() = check(View(sideBySide = false))

    @Test
    fun anotherSpeciesOnTheLeft() = check(View(Params(Species.DOG), compare = Species.CAT))

    @Test
    fun rnlAdaptationAndStrength() = check(
        View(Params(Species.CAT, adaptation = 0.7, strength = 0.6, chromaScale = ChromaScale.RNL), sideBySide = false),
    )

    @Test
    fun theAcuityBlur() = check(View(Params(acuity = true, fieldOfView = 2.0), sideBySide = false))

    @Test
    fun theMapOfDifferences() = check(View(compare = Species.DEUTERANOPE, difference = true))

    /** The share counted without waiting for the GPU, as a camera's is, is the share counted at once. */
    @Test
    fun theShareCountedLaterIsTheShareCountedAtOnce() {
        val frame = frameOf(pattern(96, 64))
        passes.upload(frame.width, frame.height, frame.pixels)
        val view = View(compare = Species.DEUTERANOPE, difference = true)
        val atOnce = assertNotNull(passes.compose(view))
        passes.composeLive(view)
        var later: Double? = null
        repeat(1000) {
            if (later == null) later = passes.takeDifferenceShare() else return@repeat
            Thread.sleep(1)
        }
        assertEquals(atOnce, assertNotNull(later, "no share after a second"), 1e-9)
    }

    /**
     * The area read back has its top row first: an image red at the top and blue at the bottom, drawn into a box at
     * the area's top left, is red at the top left and transparent outside the box.
     */
    @Test
    fun theAreaIsReadBackTopRowFirst() {
        val (width, height) = 40 to 30
        val image = Image(width, height, IntArray(width * height) { if (it / width < height / 2) RED else BLUE })
        val frame = frameOf(image)
        passes.upload(frame.width, frame.height, frame.pixels)
        passes.compose(View(Params(Species.HUMAN), sideBySide = false))
        val (areaWidth, areaHeight) = 100 to 80
        passes.draw(listOf(Box(0, 0, width, height)), areaWidth, areaHeight)
        val pixels = assertNotNull(passes.takeArea(wait = true))
        assertEquals(areaWidth * areaHeight * 4, pixels.size)

        fun at(x: Int, y: Int): List<Int> = (0 until 4).map { pixels[(y * areaWidth + x) * 4 + it].toInt() and 0xFF }
        assertEquals(listOf(255, 0, 0, 255), at(1, 1), "top left")
        assertEquals(listOf(0, 0, 255, 255), at(1, height - 2), "bottom left of the box")
        assertEquals(listOf(0, 0, 0, 0), at(areaWidth - 1, areaHeight - 1), "outside the box")
    }

    /**
     * Areas are read back without waiting, and handed over in the order they were drawn: two areas of different sizes
     * come in that order, and then nothing more.
     */
    @Test
    fun areasComeInTheOrderDrawnWithoutWaiting() {
        val image = pattern(40, 30)
        val frame = frameOf(image)
        passes.upload(frame.width, frame.height, frame.pixels)
        passes.compose(View(Params(Species.HUMAN), sideBySide = false))
        val box = listOf(Box(0, 0, image.width, image.height))
        passes.draw(box, 100, 80)
        passes.draw(box, 60, 40)
        assertEquals(2, passes.reading)
        val sizes = mutableListOf<Int>()
        repeat(1000) {
            if (sizes.size < 2) passes.takeArea()?.let { sizes += it.size } ?: Thread.sleep(1)
        }
        assertEquals(listOf(100 * 80 * 4, 60 * 40 * 4), sizes, "areas handed over within a second")
        assertEquals(0, passes.reading)
        assertNull(passes.takeArea())
    }

    private companion object {
        const val TOLERANCE = 2
        val RED = rgb(255, 0, 0)
        val BLUE = rgb(0, 0, 255)
    }
}

class EsPassesTest : PassesTest(EglContext.Api.ES)

class DesktopGlPassesTest : PassesTest(EglContext.Api.DESKTOP)
