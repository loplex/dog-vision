package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.Box
import cz.loplex.dogvision.core.View
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.math.roundToInt

/** The frames a second a recording has at most, as the Android app's have. */
private const val RECORDING_FPS = 30

/** The bits an encoder may spend on a pixel of a frame, as the Android app asks of its encoder. */
private const val BITS_PER_PIXEL = 0.1

/** The containers and codecs a recording is tried in, in this order: MP4 as the app's, else WebM. */
private val TYPES = listOf("video/mp4;codecs=avc1", "video/webm;codecs=vp9", "video/webm")

/**
 * A recording of the view, as the Android app's: its images, at the size of the frame and in the arrangement they had
 * when it started, without captions or sound.
 *
 * WebGL draws into its own canvas only, so the images are rendered a second time, by passes of their own in a WebGL 2
 * context of their own, from the same frames, and a MediaRecorder records that canvas, which the page never shows.
 * Reading the images back from the page's context instead costs the main thread a copy of every image each frame,
 * which slows the page itself on a phone. The canvas is drawn again on every frame of the page, so that a still photo
 * is recorded at the recording's rate too.
 */
internal class Recording private constructor(
    private val canvas: HTMLCanvasElement,
    private val passes: Passes,
    private val boxes: List<Box>,
    private val recorder: dynamic,
    private val type: String,
    onFailed: (String) -> Unit,
) {
    private val chunks = mutableListOf<Blob>()
    private var stopped = false

    init {
        recorder.ondataavailable = { event: dynamic ->
            if ((event.data.size as Int) > 0) chunks.add(event.data.unsafeCast<Blob>())
        }
        recorder.onerror = { event: dynamic ->
            if (!stopped) {
                stopped = true
                canvas.remove()
                onFailed(event.error?.message as String? ?: "")
            }
        }
        recorder.start(1000)
        drawEachFrame()
    }

    /** Makes the photo's pixels, as [Passes.upload] takes them, the frame to record. */
    fun upload(width: Int, height: Int, pixels: Uint8Array) = passes.upload(width, height, pixels)

    /** Makes the frame [video] shows the one to record, as [Passes.upload] takes it. */
    fun upload(video: HTMLVideoElement, mirrored: Boolean, rotation: Int) = passes.upload(video, mirrored, rotation)

    /**
     * Renders the images of [view] of the frame uploaded last, which are recorded from then on; nothing before a frame
     * is, as a camera's or a video's comes a moment after the recording starts.
     */
    fun compose(view: View) {
        if (passes.frameWidth > 0) passes.composeImages(view)
    }

    /** Stops recording, and hands the video to [onDone] once the browser has encoded all of it. */
    fun stop(onDone: (Blob) -> Unit) {
        if (stopped) return
        stopped = true
        recorder.onstop = { onDone(Blob(chunks.toTypedArray(), BlobPropertyBag(type = type))) }
        recorder.stop()
        canvas.remove()
    }

    /** The file name's extension for what the browser records into. */
    val extension: String get() = if (type.startsWith("video/mp4")) "mp4" else "webm"

    private fun drawEachFrame() {
        window.requestAnimationFrame {
            if (!stopped) {
                passes.draw(boxes, canvas.width, canvas.height)
                drawEachFrame()
            }
        }
    }

    companion object {
        /** The type the browser records a canvas in, or null if it records none. */
        private val supportedType: String? by lazy {
            val recorder = window.asDynamic().MediaRecorder
            if (recorder == undefined || js("HTMLCanvasElement.prototype.captureStream") == undefined) {
                null
            } else {
                TYPES.firstOrNull { recorder.isTypeSupported(it) as Boolean }
            }
        }

        /** Whether the browser can record the view at all. */
        val supported: Boolean get() = supportedType != null

        /**
         * Starts recording [count] images of [width] x [height], side by side or one above another as [arrangement]
         * says; throws IllegalStateException if the browser cannot. [onFailed] is handed the browser's message if the
         * recorder fails later, which stops it.
         */
        fun start(
            count: Int,
            width: Int,
            height: Int,
            arrangement: Arrangement,
            onFailed: (String) -> Unit,
        ): Recording {
            val type = checkNotNull(supportedType) { "no MediaRecorder for a canvas" }
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            // In the document, where a browser keeps drawing it, but out of sight and of assistive technology.
            canvas.setAttribute("aria-hidden", "true")
            canvas.style.cssText = "position: fixed; left: -100000px; top: 0"
            canvas.width = if (arrangement == Arrangement.ROW) width * count else width
            canvas.height = if (arrangement == Arrangement.ROW) height else height * count
            val row = arrangement == Arrangement.ROW
            val boxes = List(count) {
                if (row) Box(width * it, 0, width, height) else Box(0, height * it, width, height)
            }
            val gl = canvas.getContext(
                "webgl2",
                js("({ alpha: false, antialias: false, depth: false, stencil: false })"),
            )?.unsafeCast<WebGL2RenderingContext>() ?: error("no WebGL 2 context for the recording")
            val passes = Passes(gl)
            document.body?.appendChild(canvas)
            val stream = canvas.asDynamic().captureStream(RECORDING_FPS)
            val bits = (canvas.width * canvas.height * RECORDING_FPS * BITS_PER_PIXEL).roundToInt()
            val options = js("({})")
            options.mimeType = type
            options.videoBitsPerSecond = bits
            val recorder = try {
                js("new MediaRecorder(stream, options)")
            } catch (error: Throwable) {
                canvas.remove()
                throw IllegalStateException(error.message)
            }
            return Recording(canvas, passes, boxes, recorder, type, onFailed)
        }
    }
}
