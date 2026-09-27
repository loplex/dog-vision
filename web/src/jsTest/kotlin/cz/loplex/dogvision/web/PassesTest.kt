package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.blue
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.green
import cz.loplex.dogvision.core.red
import cz.loplex.dogvision.testing.coreImages
import cz.loplex.dogvision.testing.coreMap
import cz.loplex.dogvision.testing.pattern
import cz.loplex.dogvision.testing.worstChannelDifference
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WebGL 2 renders what core's CPU pipeline renders, within [TOLERANCE] of 8 bits, as the Android app's ViewRendererTest
 * holds its OpenGL ES renderer to it.
 */
class PassesTest {
    private fun upload(image: Image) {
        val pixels = Uint8Array(image.width * image.height * 4)
        image.pixels.forEachIndexed { i, pixel ->
            pixels[i * 4] = red(pixel).toByte()
            pixels[i * 4 + 1] = green(pixel).toByte()
            pixels[i * 4 + 2] = blue(pixel).toByte()
            pixels[i * 4 + 3] = -1
        }
        passes.upload(image.width, image.height, pixels)
    }

    private fun assertClose(expected: Image, actual: Image, what: String) {
        val worst = worstChannelDifference(expected, actual)
        assertTrue(worst <= TOLERANCE, "$what: a channel differs by $worst")
    }

    /**
     * The images WebGL renders are core's, within [TOLERANCE]. The map of differences is held to the map core draws
     * from WebGL's own two images: a pixel near the threshold of one just-noticeable difference may cross it for images
     * a step apart, and then turns from grey to red.
     */
    private fun check(view: View, image: Image = pattern(96, 64)) {
        upload(image)
        val actualShare = passes.compose(view)
        val (expected, share) = coreImages(image, view)
        val actual = passes.readImages(view.images)
        assertEquals(expected.size, actual.size)
        expected.zip(actual).take(2).forEachIndexed { i, (e, a) -> assertClose(e, a, "$view image $i") }
        if (share == null) {
            assertNull(actualShare)
            return
        }
        assertNotNull(actualShare)
        val (left, right, map) = actual
        val (expectedMap, expectedShare) = coreMap(left, right)
        assertClose(expectedMap, map, "$view map")
        assertEquals(expectedShare, actualShare, 1e-9)
        assertEquals(share, actualShare, 0.02)
    }

    @Test
    fun theSimulationAlone() = check(View(sideBySide = false))

    @Test
    fun theOriginalBesideTheSimulation() = check(View(Params(Species.HORSE)))

    @Test
    fun anotherSpeciesOnTheLeft() = check(View(Params(Species.DOG), compare = Species.CAT))

    @Test
    fun rnlAdaptationAndStrength() =
        check(View(Params(adaptation = 0.5, strength = 0.75, chromaScale = ChromaScale.RNL), sideBySide = false))

    @Test
    fun aMonochromat() = check(View(Params(Species.HARBOUR_SEAL), sideBySide = false))

    @Test
    fun aTrichromatOnTheRnlScale() =
        check(View(Params(Species.MACAQUE, chromaScale = ChromaScale.RNL), sideBySide = false))

    @Test
    fun theAcuityBlur() = check(View(Params(acuity = true, fieldOfView = 2.0), sideBySide = false))

    @Test
    fun theCattleBlurDiffersByDirection() =
        check(View(Params(Species.COW, acuity = true, fieldOfView = 4.0), sideBySide = false))

    @Test
    fun theMapOfDifferences() = check(View(compare = Species.DEUTERANOPE, difference = true))

    @Test
    fun theMapOfDifferencesWithBlur() = check(View(Params(acuity = true, fieldOfView = 3.0), difference = true))

    @Test
    fun aPhotoOfAnotherSizeReplacesTheFirst() {
        check(View(sideBySide = false), pattern(40, 24))
        check(View(sideBySide = false), pattern(24, 40))
    }

    /** A share counted without waiting for the GPU, as a camera's is, is the share counted at once for a photo. */
    @Test
    fun theShareCountedLaterIsTheShareCountedAtOnce(): Promise<Unit> {
        val view = View(compare = Species.DEUTERANOPE, difference = true)
        upload(pattern(96, 64))
        val atOnce = assertNotNull(passes.compose(view))
        passes.composeLive(view)
        assertNull(passes.takeDifferenceShare(), "a share handed over before the count was started")
        return Promise { resolve, reject ->
            // WebGL signals a fence only between tasks of the page, so the test waits for it in tasks of its own.
            fun poll(tries: Int) {
                val later = passes.takeDifferenceShare()
                when {
                    later != null -> try {
                        assertEquals(atOnce, later, 1e-9)
                        assertFalse(passes.counting)
                        resolve(Unit)
                    } catch (error: Throwable) {
                        reject(error)
                    }

                    tries == 0 -> reject(AssertionError("no share handed over"))

                    else -> window.setTimeout({ poll(tries - 1) }, 10)
                }
            }
            poll(500)
        }
    }

    /**
     * A video's frame, as the camera's are, is uploaded as it is or mirrored: a frame red on the left and blue on the
     * right, as a video of a canvas carries it. Only halves are compared, as a video keeps colour at half resolution.
     */
    @Test
    fun aVideoFrameIsUploadedAsItIsOrMirrored(): Promise<Unit> {
        val source = document.createElement("canvas") as HTMLCanvasElement
        source.width = 64
        source.height = 32
        val context = source.getContext("2d").unsafeCast<CanvasRenderingContext2D>()
        fun paint() {
            context.fillStyle = "rgb(200, 10, 10)"
            context.fillRect(0.0, 0.0, 32.0, 32.0)
            context.fillStyle = "rgb(10, 10, 200)"
            context.fillRect(32.0, 0.0, 32.0, 32.0)
        }
        paint()
        val video = document.createElement("video") as HTMLVideoElement
        video.muted = true
        assertFalse(passes.upload(video, mirrored = false), "a frame uploaded from a video that has none")
        video.srcObject = source.asDynamic().captureStream()
        return Promise { resolve, reject ->
            video.asDynamic().requestVideoFrameCallback { _: Double, _: dynamic ->
                try {
                    assertEquals(listOf(RED, BLUE), halves(video, mirrored = false))
                    assertEquals(listOf(BLUE, RED), halves(video, mirrored = true))
                    resolve(Unit)
                } catch (error: Throwable) {
                    reject(error)
                }
            }
            video.play().catch(reject)
            // A video of a canvas has a frame only once the canvas is painted after it starts.
            window.setTimeout(::paint, 50)
        }
    }

    /** A video's frame larger than a photo is shown, as a large video's or a camera's may be, is scaled down as one. */
    @Test
    fun aLargeVideoFrameIsScaledDownAsAPhotoIs(): Promise<Unit> {
        val source = document.createElement("canvas") as HTMLCanvasElement
        source.width = 1600
        source.height = 800
        val context = source.getContext("2d").unsafeCast<CanvasRenderingContext2D>()
        fun paint() {
            context.fillStyle = "rgb(200, 10, 10)"
            context.fillRect(0.0, 0.0, 1600.0, 800.0)
        }
        paint()
        val video = document.createElement("video") as HTMLVideoElement
        video.muted = true
        video.srcObject = source.asDynamic().captureStream()
        return Promise { resolve, reject ->
            video.asDynamic().requestVideoFrameCallback { _: Double, _: dynamic ->
                try {
                    assertTrue(passes.upload(video, mirrored = false))
                    assertEquals(PREVIEW_LONGEST_SIDE to 640, passes.frameWidth to passes.frameHeight)
                    resolve(Unit)
                } catch (error: Throwable) {
                    reject(error)
                }
            }
            video.play().catch(reject)
            window.setTimeout(::paint, 50)
        }
    }

    /**
     * Where the browser draws no video into a 2D canvas, a frame is uploaded as its file stores it, scaled down on the
     * GPU and turned by the passes: red on the left and blue on the right, turned clockwise, is red above and blue
     * below.
     */
    @Test
    fun aVideoFrameAsStoredIsScaledAndTurnedByThePasses(): Promise<Unit> {
        val source = document.createElement("canvas") as HTMLCanvasElement
        source.width = 64
        source.height = 32
        val context = source.getContext("2d").unsafeCast<CanvasRenderingContext2D>()
        fun paint() {
            context.fillStyle = "rgb(200, 10, 10)"
            context.fillRect(0.0, 0.0, 32.0, 32.0)
            context.fillStyle = "rgb(10, 10, 200)"
            context.fillRect(32.0, 0.0, 32.0, 32.0)
        }
        paint()
        val video = document.createElement("video") as HTMLVideoElement
        video.muted = true
        video.srcObject = source.asDynamic().captureStream()
        fun ends(rotation: Int, scale: Double): List<String> {
            passes.uploadStored(video, 64, 32, rotation, scale, mirrored = false)
            passes.compose(View())
            val image = passes.readImages(1).single()
            val quarter = rotation % 180 != 0
            val (width, height) = (64 * scale).toInt() to (32 * scale).toInt()
            assertEquals(if (quarter) height to width else width to height, image.width to image.height)
            // The first and the last eighth along the side the halves now lie on.
            val points = if (quarter) {
                listOf(image.width / 2 to image.height / 8, image.width / 2 to image.height * 7 / 8)
            } else {
                listOf(image.width / 8 to image.height / 2, image.width * 7 / 8 to image.height / 2)
            }
            return points.map { (x, y) -> colour(image[x, y]) }
        }
        return Promise { resolve, reject ->
            video.asDynamic().requestVideoFrameCallback { _: Double, _: dynamic ->
                try {
                    assertEquals(listOf(RED, BLUE), ends(rotation = 0, scale = 1.0), "as stored")
                    assertEquals(listOf(RED, BLUE), ends(rotation = 90, scale = 1.0), "turned clockwise")
                    assertEquals(listOf(BLUE, RED), ends(rotation = 180, scale = 1.0), "turned half round")
                    assertEquals(listOf(BLUE, RED), ends(rotation = 270, scale = 1.0), "turned anticlockwise")
                    assertEquals(listOf(RED, BLUE), ends(rotation = 90, scale = 0.5), "scaled and turned")
                    resolve(Unit)
                } catch (error: Throwable) {
                    reject(error)
                }
            }
            video.play().catch(reject)
            window.setTimeout(::paint, 50)
        }
    }

    /** The colour of the left and the right half of the original image of [video]'s frame uploaded. */
    private fun halves(video: HTMLVideoElement, mirrored: Boolean): List<String> {
        assertTrue(passes.upload(video, mirrored))
        passes.compose(View())
        val original = passes.readImages(1).single()
        assertEquals(64 to 32, original.width to original.height)
        return listOf(8, 56).map { x -> colour(original[x, 16]) }
    }

    /** [pixel] named as the red or the blue a video of the test's canvas carries, or its values. */
    private fun colour(pixel: Int) = when {
        abs(red(pixel) - 200) <= VIDEO_TOLERANCE && abs(blue(pixel) - 10) <= VIDEO_TOLERANCE -> RED
        abs(red(pixel) - 10) <= VIDEO_TOLERANCE && abs(blue(pixel) - 200) <= VIDEO_TOLERANCE -> BLUE
        else -> "rgb(${red(pixel)}, ${green(pixel)}, ${blue(pixel)})"
    }

    private companion object {
        /** How far a video's colour may be from its canvas's, having been through YUV and back. */
        const val VIDEO_TOLERANCE = 4
        const val RED = "red"
        const val BLUE = "blue"

        /** One step for the rounding of a float; one more where a blur keeps its half-way result in 8 bits. */
        const val TOLERANCE = 2

        /** One context for every test: a browser keeps only a few at a time, and loses the oldest. */
        val passes by lazy {
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            Passes(checkNotNull(canvas.getContext("webgl2")) { "No WebGL 2" }.unsafeCast<WebGL2RenderingContext>())
        }
    }
}
