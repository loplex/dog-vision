package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.Box
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.gl.GL_LINEAR
import cz.loplex.dogvision.gl.GL_RGBA
import cz.loplex.dogvision.gl.GL_RGBA8
import cz.loplex.dogvision.gl.GL_TEXTURE_2D
import cz.loplex.dogvision.gl.GL_UNSIGNED_BYTE
import cz.loplex.dogvision.gl.Target
import cz.loplex.dogvision.gl.ViewPasses
import cz.loplex.dogvision.gl.texture
import kotlinx.browser.document
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.WebGLRenderingContext.Companion.COLOR_BUFFER_BIT
import org.khronos.webgl.WebGLRenderingContext.Companion.FRAMEBUFFER
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLVideoElement
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The page's rendering of a photo or a camera's frames: gl's [ViewPasses], as the Android app runs them, over WebGL 2.
 * The passes compile when this is made, and throw IllegalStateException if the GPU's driver cannot compile or link one.
 *
 * A photo comes upright, as the browser decodes it, and is turned upright once all the same, as the app turns every
 * frame. It does not change while it is shown, so the share of pixels its map of differences marks is counted at once.
 * A camera's or a video's frame comes upright too, the browser having turned it as the display is or as the video's
 * rotation says, unless [upload] finds the browser cannot, and is mirrored if need be; its share is counted without
 * waiting for the GPU, and handed over a frame or two later, as in the app.
 */
internal class Passes(private val gl: WebGL2RenderingContext) {
    private val webGl = WebGl(gl)
    private val passes = ViewPasses(webGl).apply { create() }

    /** The photo or the camera's frame as uploaded, before it is turned upright. */
    private val raw = texture(webGl)

    /** Makes [pixels], tightly packed RGBA of [width] x [height] with the first row first, the photo to render. */
    fun upload(width: Int, height: Int, pixels: Uint8Array) {
        webGl.bindTexture(GL_TEXTURE_2D, raw)
        val bytes = Int8Array(pixels.buffer, pixels.byteOffset, pixels.length).unsafeCast<ByteArray>()
        webGl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
        passes.turnUpright(raw, width, height, rotation = 0, mirrored = false)
    }

    /** Where a frame is drawn before it is uploaded, when it is larger than [PREVIEW_LONGEST_SIDE] or turned. */
    private val scaled by lazy {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas to canvas.getContext("2d").unsafeCast<CanvasRenderingContext2D>()
    }

    /** A video's frame as its file stores it, where the browser draws no video into a 2D canvas, and scaled down. */
    private val stored = Target(webGl)
    private val storedScaled = Target(webGl, filtered = true)

    /** The video last drawn into [scaled], and whether the browser drew it there as transparent black. */
    private var drawnVideo: HTMLVideoElement? = null
    private var drawnBlank = false

    /**
     * Makes the frame [video] shows the one to render, scaled down to [PREVIEW_LONGEST_SIDE] as a photo is and
     * mirrored if [mirrored]; false, with nothing changed, while the video has no frame yet.
     *
     * A video whose file says to turn it by [rotation] degrees clockwise, as a phone held upright records it, is drawn
     * into a 2D canvas first, as one to scale down is: Firefox 156 turns such a frame there, but uploads it to WebGL as
     * it is stored, at the size it gives as the turned one's. Firefox 156 on Android draws a video into a 2D canvas as
     * transparent black, though; there the frame is uploaded as stored, scaled down bilinearly on the GPU, and turned
     * by the passes.
     */
    fun upload(video: HTMLVideoElement, mirrored: Boolean, rotation: Int = 0): Boolean {
        val longest = max(video.videoWidth, video.videoHeight)
        if (longest == 0) return false
        val scale = minOf(1.0, PREVIEW_LONGEST_SIDE.toDouble() / longest)
        val width = max(1, (video.videoWidth * scale).roundToInt())
        val height = max(1, (video.videoHeight * scale).roundToInt())
        if (scale == 1.0 && rotation == 0) {
            webGl.bindTexture(GL_TEXTURE_2D, raw)
            gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, video)
            passes.turnUpright(raw, width, height, rotation = 0, mirrored = mirrored)
            return true
        }
        if (drawnVideo !== video || !drawnBlank) {
            val (canvas, context) = scaled
            if (canvas.width != width || canvas.height != height) {
                canvas.width = width
                canvas.height = height
            }
            context.imageSmoothingEnabled = true
            context.asDynamic().imageSmoothingQuality = "high"
            context.drawImage(video, 0.0, 0.0, width.toDouble(), height.toDouble())
            if (drawnVideo !== video) {
                drawnVideo = video
                // A video's frame is opaque, so a pixel left transparent was not drawn.
                drawnBlank = context.getImageData(0.0, 0.0, 1.0, 1.0).data.asDynamic()[3] == 0
            }
            if (!drawnBlank) {
                webGl.bindTexture(GL_TEXTURE_2D, raw)
                gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, canvas)
                passes.turnUpright(raw, width, height, rotation = 0, mirrored = mirrored)
                return true
            }
        }
        // The size the browser gives is the turned one's.
        val quarter = rotation % 180 != 0
        val storedWidth = if (quarter) video.videoHeight else video.videoWidth
        val storedHeight = if (quarter) video.videoWidth else video.videoHeight
        uploadStored(video, storedWidth, storedHeight, rotation, scale, mirrored)
        return true
    }

    /**
     * Uploads [video]'s frame as its file stores it, [storedWidth] x [storedHeight] and turned from upright by
     * [rotation], scales it by [scale], and has the passes turn it upright.
     */
    internal fun uploadStored(
        video: HTMLVideoElement,
        storedWidth: Int,
        storedHeight: Int,
        rotation: Int,
        scale: Double,
        mirrored: Boolean,
    ) {
        stored.ensure(storedWidth, storedHeight)
        webGl.bindTexture(GL_TEXTURE_2D, stored.texture)
        gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, video)
        if (scale == 1.0) {
            passes.turnUpright(stored.texture, storedWidth, storedHeight, rotation, mirrored)
            return
        }
        val width = max(1, (storedWidth * scale).roundToInt())
        val height = max(1, (storedHeight * scale).roundToInt())
        storedScaled.ensure(width, height)
        webGl.bindFramebuffer(Gl2.READ_FRAMEBUFFER, stored.framebuffer)
        webGl.bindFramebuffer(Gl2.DRAW_FRAMEBUFFER, storedScaled.framebuffer)
        gl.blitFramebuffer(0, 0, storedWidth, storedHeight, 0, 0, width, height, COLOR_BUFFER_BIT, GL_LINEAR)
        passes.turnUpright(storedScaled.texture, width, height, rotation, mirrored)
    }

    /** The size of the frame uploaded last, upright. */
    val frameWidth: Int get() = passes.frame.width
    val frameHeight: Int get() = passes.frame.height

    /**
     * Renders every image of [view] of the photo, and returns the share of pixels its map of differences marks if it
     * shows one.
     */
    fun compose(view: View): Double? {
        passes.compose(view)
        return if (view.sideBySide && view.difference) passes.differenceShare() else null
    }

    /**
     * Renders every image of [view] of a camera's frame, and asks for the share of pixels its map of differences marks
     * if it shows one, which [takeDifferenceShare] hands over.
     */
    fun composeLive(view: View) {
        passes.compose(view)
        if (view.sideBySide && view.difference) passes.countDifferences()
    }

    /** Renders every image of [view] of the frame, counting nothing, as a recording of them needs. */
    fun composeImages(view: View) = passes.compose(view)

    /** Whether a share asked for by [composeLive] has not been handed over yet. */
    val counting: Boolean get() = passes.counting

    /** The share asked for by [composeLive] once the GPU has counted it, else null; call it once a frame. */
    fun takeDifferenceShare(): Double? = passes.takeDifferenceShare()

    /**
     * Clears the canvas, [width] x [height], and draws the first images composed into [boxes], one each, measured from
     * its top left corner.
     */
    fun draw(boxes: List<Box>, width: Int, height: Int) {
        gl.bindFramebuffer(FRAMEBUFFER, null)
        gl.viewport(0, 0, width, height)
        gl.clearColor(0f, 0f, 0f, 0f)
        gl.clear(COLOR_BUFFER_BIT)
        passes.draw(boxes, width, height)
    }

    /** The first [count] images composed, read back from the GPU, left to right or top to bottom. */
    fun readImages(count: Int): List<Image> = passes.readImages(count)
}
