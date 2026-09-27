package cz.loplex.dogvision.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import cz.loplex.dogvision.cli.WindowsGl
import cz.loplex.dogvision.core.ScreenLayout
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.layOut
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import org.jetbrains.skia.Image as SkiaImage

/** The images of [view] drawn into [layout]'s boxes on a transparent [bitmap] of the area, and the share it counted. */
class Picture(val bitmap: ImageBitmap, val layout: ScreenLayout, val view: View, val differenceShare: Double?)

/** The area the images are laid out on, and the height of a caption under each and the gap between them, in pixels. */
data class Area(val width: Int, val height: Int, val captionHeight: Int, val gap: Int)

/**
 * Renders the view of the frame shown last on a thread of its own, which holds a [GlContext], opened as [windowsGl]
 * asks on Windows, and [Passes], and hands each picture to [onPicture] on that thread. [onFailure] is told, once, why
 * nothing can be drawn, if GL cannot be set up.
 *
 * It renders when something has changed: a frame, the view or the area; a live frame's share of differing pixels,
 * counted without waiting for the GPU as the Android app counts it, comes with a later picture. The area drawn is read
 * back without waiting for the GPU either, [READS] at a time: its picture comes once the GPU has read it, and the
 * frames after it are uploaded and composed meanwhile.
 */
class Renderer(
    private val windowsGl: WindowsGl?,
    private val onPicture: (Picture) -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val lock = Object()

    /** The frame shown last, not uploaded yet, copied into a buffer the renderer's thread swaps with its own. */
    private var pending: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var pendingWidth = 0
    private var pendingHeight = 0
    private var pendingLive = false
    private var framePending = false
    private var view: View? = null
    private var area: Area? = null
    private var changed = false
    private var closed = false

    private val thread = thread(name = "dog-vision-gl", isDaemon = true) { run() }

    /**
     * Makes [frame] the one to render, copying it, so that its buffer can be reused as soon as this returns; a [live]
     * frame is a video's or a camera's, followed by others, a frame that is not is a photo's.
     */
    fun show(frame: Frame, live: Boolean) = synchronized(lock) {
        val size = frame.width * frame.height * 4
        if (pending.capacity() < size) pending = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        pending.clear().put(frame.pixels.duplicate()).flip()
        pendingWidth = frame.width
        pendingHeight = frame.height
        pendingLive = live
        framePending = true
        changed = true
        lock.notifyAll()
    }

    fun setView(view: View) = update { this.view = view }

    fun setArea(area: Area) = update { this.area = area }

    private inline fun update(change: () -> Unit) = synchronized(lock) {
        change()
        changed = true
        lock.notifyAll()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        thread.join(CLOSE_WAIT_MILLIS)
    }

    private fun run() {
        val (context, passes) = try {
            GlContext.open(windowsGl, ::Passes)
        } catch (error: IllegalStateException) {
            onFailure(error.message.orEmpty())
            return
        } catch (error: UnsatisfiedLinkError) {
            onFailure(error.message.orEmpty())
            return
        }
        if (context.software) System.err.println("OpenGL renders on the CPU here: ${context.renderer}")
        try {
            render(passes)
            passes.release()
        } finally {
            context.close()
        }
    }

    /** An area drawn and being read back, with what its picture says of it. */
    private class Drawn(val layout: ScreenLayout, val view: View, var share: Double?, val area: Area)

    /** The loop that renders until [close]. */
    private fun render(passes: Passes) {
        var uploading: ByteBuffer = ByteBuffer.allocateDirect(0)
        var live = false
        var hasFrame = false
        var composed: View? = null
        var share: Double? = null
        var last: Picture? = null
        var drawnArea: Area? = null
        // The areas being read back, in the order Passes hands them over.
        val drawing = ArrayDeque<Drawn>()

        fun handOver(pixels: ByteArray) {
            val drawn = drawing.removeFirst()
            val info = ImageInfo(drawn.area.width, drawn.area.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            val bitmap = SkiaImage.makeRaster(info, pixels, drawn.area.width * 4).toComposeImageBitmap()
            val picture = Picture(bitmap, drawn.layout, drawn.view, drawn.share)
            last = picture
            onPicture(picture)
        }
        while (true) {
            var frameWidth = 0
            var frameHeight = 0
            val newFrame: Boolean
            val view: View?
            val area: Area?
            synchronized(lock) {
                while (!closed && !changed) {
                    // A read or a count under way is asked after, and a live frame may be long in coming.
                    if (passes.reading > 0 || (live && passes.counting)) {
                        lock.wait(POLL_MILLIS)
                        break
                    }
                    lock.wait()
                }
                if (closed) return
                changed = false
                newFrame = framePending
                if (newFrame) {
                    framePending = false
                    val swapped = uploading
                    uploading = pending
                    pending = swapped
                    frameWidth = pendingWidth
                    frameHeight = pendingHeight
                    live = pendingLive
                }
                view = this.view
                area = this.area
            }
            while (true) handOver(passes.takeArea() ?: break)
            if (newFrame) {
                passes.upload(frameWidth, frameHeight, uploading)
                hasFrame = true
            }
            if (!hasFrame || view == null || area == null || area.width <= 0 || area.height <= 0) continue
            val counts = view.sideBySide && view.difference
            val recomposed = newFrame || view != composed
            if (recomposed) {
                if (live) {
                    passes.composeLive(view)
                    if (!counts) share = null
                } else {
                    share = passes.compose(view)
                }
                composed = view
            }
            var counted = false
            if (live && passes.counting) {
                passes.takeDifferenceShare()?.let {
                    share = it
                    counted = true
                }
            }
            val shown = share.takeIf { counts }
            if (recomposed || area != drawnArea) {
                if (passes.reading >= READS) handOver(checkNotNull(passes.takeArea(wait = true)))
                val layout = layOut(
                    area.width,
                    area.height,
                    passes.frameWidth,
                    passes.frameHeight,
                    view.images,
                    area.captionHeight,
                    area.gap,
                )
                passes.draw(layout.images, area.width, area.height)
                drawing.addLast(Drawn(layout, view, shown, area))
                drawnArea = area
            } else if (counted) {
                // The share goes with the picture drawn last, or, if that is shown already, with that picture again.
                val latest = drawing.lastOrNull()
                val previous = last
                if (latest != null) {
                    latest.share = shown
                } else if (previous != null) {
                    last = Picture(previous.bitmap, previous.layout, view, shown).also(onPicture)
                }
            }
        }
    }

    private companion object {
        /** How many areas may be read back at a time; one more waits for the first. */
        const val READS = 2
        const val POLL_MILLIS = 2L
        const val CLOSE_WAIT_MILLIS = 2000L
    }
}
