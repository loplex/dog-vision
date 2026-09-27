package cz.loplex.dogvision.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One image to show, as tightly packed RGBA bytes of 8-bit sRGB, the way the camera and a Bitmap
 * hand them over. [rotation] is how far it must be turned clockwise to stand upright, and
 * [mirrored] says whether it must then be flipped left to right, as a front camera's image is.
 */
class Frame(val width: Int, val height: Int, val pixels: ByteBuffer) {
    var rotation = 0
    var mirrored = false

    /** The size once it stands upright. */
    val uprightWidth: Int get() = if (rotation % 180 == 0) width else height
    val uprightHeight: Int get() = if (rotation % 180 == 0) height else width

    /** The packed 0xAARRGGBB pixel at column x and row y as the frame stands upright. */
    fun uprightPixel(x: Int, y: Int): Int {
        val column = if (mirrored) uprightWidth - 1 - x else x
        val (rawX, rawY) = when (rotation) {
            90 -> y to height - 1 - column
            180 -> width - 1 - column to height - 1 - y
            270 -> width - 1 - y to column
            else -> column to y
        }
        val offset = (rawY * width + rawX) * 4
        return (0xFF shl 24) or
            ((pixels.get(offset).toInt() and 0xFF) shl 16) or
            ((pixels.get(offset + 1).toInt() and 0xFF) shl 8) or
            (pixels.get(offset + 2).toInt() and 0xFF)
    }

    companion object {
        fun allocate(width: Int, height: Int): Frame =
            Frame(width, height, ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder()))
    }
}

/**
 * Hands the newest frame from a producer (the camera, a photo) to the GL thread, reusing a few
 * buffers so that a camera running at 30 frames a second does not allocate one per frame.
 *
 * A frame that has not been drawn when a newer one comes is dropped: the view shows the newest.
 * Each source that opens gets a generation of its own, and a frame from an earlier one, such as the
 * camera's last frame arriving after a photo was opened, is dropped too.
 */
class FrameExchange {
    private val free = ArrayDeque<Frame>()
    private var latest: Frame? = null
    private var shown: Frame? = null
    private var allocated = 0
    private var generation = 0

    /** Starts a new source; frames published with an earlier generation are dropped from now on. */
    @Synchronized
    fun open(): Int = ++generation

    /** A frame of the given size to fill, or null if all of them are in use. */
    @Synchronized
    fun obtain(width: Int, height: Int): Frame? {
        val stale = free.filter { it.width != width || it.height != height }
        free.removeAll(stale)
        allocated -= stale.size
        free.removeFirstOrNull()?.let { return it }
        if (allocated >= MAX_FRAMES) return null
        allocated++
        return Frame.allocate(width, height)
    }

    /** Told, on the producer's thread, that a new frame is waiting to be drawn. */
    @Volatile
    var onPublish: (() -> Unit)? = null

    /**
     * Makes a filled frame of the source of [generation] the newest; the one it replaces, if not
     * drawn yet, is dropped, and so is the frame itself if another source has opened since.
     */
    fun publish(frame: Frame, generation: Int) {
        synchronized(this) {
            if (generation != this.generation) {
                free.addLast(frame)
                return
            }
            latest?.let(free::addLast)
            latest = frame
        }
        onPublish?.invoke()
    }

    /** The newest frame for the GL thread to draw, if there is one it has not taken yet. */
    @Synchronized
    fun take(): Frame? {
        val frame = latest ?: return null
        latest = null
        shown?.let(free::addLast)
        shown = frame
        return frame
    }

    /** The frame taken last, which stays shown until a newer one is taken. */
    @Synchronized
    fun current(): Frame? = shown

    /** Forgets every frame, as when the source changes to one of another size. */
    @Synchronized
    fun clear() {
        free.clear()
        latest = null
        shown = null
        allocated = 0
    }

    private companion object {
        const val MAX_FRAMES = 3
    }
}
