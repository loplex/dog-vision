package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.core.Box
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.gl.GL_FRAMEBUFFER
import cz.loplex.dogvision.gl.GL_PIXEL_PACK_BUFFER
import cz.loplex.dogvision.gl.GL_RGBA
import cz.loplex.dogvision.gl.GL_RGBA8
import cz.loplex.dogvision.gl.GL_STREAM_READ
import cz.loplex.dogvision.gl.GL_TEXTURE_2D
import cz.loplex.dogvision.gl.GL_UNSIGNED_BYTE
import cz.loplex.dogvision.gl.Target
import cz.loplex.dogvision.gl.ViewPasses
import cz.loplex.dogvision.gl.texture
import java.nio.ByteBuffer

/**
 * The window's rendering of a photo or a video's or a camera's frames: gl's [ViewPasses],
 * as the Android app and the web page run them, over [gl] in the context current on this thread,
 * drawn into a framebuffer of the window's area and read back to be shown.\
 * The passes compile when this is made, and throw IllegalStateException if the GPU's driver cannot compile or link one.
 *
 * A frame comes upright, as ffmpeg turns a video and as a photo is turned when it is read,
 * so it is turned upright as it is, as the app turns every frame,
 * and mirrored if it is to be, as the app mirrors its front camera's.
 */
internal class Passes(private val gl: DesktopGl) {
    private val viewPasses = ViewPasses(gl).apply { create() }

    /** The frame as uploaded, before it is turned upright. */
    private val raw = texture(gl)

    /** The window's area, which the images are drawn into. */
    private val area = Target(gl)

    /** An area drawn and being read back: the pixel pack buffer the GPU reads it into, and the fence signalled then. */
    private class Read(val buffer: Int, val fence: Long, val width: Int, val height: Int)

    /** The areas being read back, first the one drawn first, and the buffers no read uses now. */
    private val reads = ArrayDeque<Read>()
    private val spareBuffers = ArrayDeque<Int>()

    /** The size of the frame as uploaded. */
    private var rawWidth = 0
    private var rawHeight = 0

    /**
     * Makes [pixels], tightly packed RGBA of [width] x [height] with the top row first,
     * the frame to render, mirrored if [mirrored].
     */
    fun upload(width: Int, height: Int, pixels: ByteBuffer, mirrored: Boolean = false) {
        gl.bindTexture(GL_TEXTURE_2D, raw)
        gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        rawWidth = width
        rawHeight = height
        mirror(mirrored)
    }

    /** Makes the frame uploaded last the frame to render again, mirrored if [mirrored]; it is composed anew then. */
    fun mirror(mirrored: Boolean) = viewPasses.turnUpright(raw, rawWidth, rawHeight, rotation = 0, mirrored = mirrored)

    /** The size of the frame uploaded last. */
    val frameWidth: Int get() = viewPasses.frame.width
    val frameHeight: Int get() = viewPasses.frame.height

    /**
     * Renders every image of [view] of a photo,
     * and returns the share of pixels its map of differences marks if it shows one,
     * counted at once as a photo that does not change can wait for.
     */
    fun compose(view: View): Double? {
        viewPasses.compose(view)
        return if (view.sideBySide && view.difference) viewPasses.differenceShare() else null
    }

    /**
     * Renders every image of [view] of a video's or a camera's frame,
     * and asks for the share of pixels its map of differences marks if it shows one,
     * which [takeDifferenceShare] hands over.
     */
    fun composeLive(view: View) {
        viewPasses.compose(view)
        if (view.sideBySide && view.difference) viewPasses.countDifferences()
    }

    /** Whether a share asked for by [composeLive] has not been handed over yet. */
    val counting: Boolean get() = viewPasses.counting

    /** The share asked for by [composeLive] once the GPU has counted it, else null; call it once a frame. */
    fun takeDifferenceShare(): Double? = viewPasses.takeDifferenceShare()

    /** The first [count] images composed, read back from the GPU, left to right or top to bottom. */
    fun readImages(count: Int): List<Image> = viewPasses.readImages(count)

    /**
     * Draws the first images composed into [boxes] of an area of [width] x [height], transparent elsewhere,
     * and starts reading it back without waiting for the GPU, which [takeArea] hands over.
     */
    @Suppress("MagicNumber")
    fun draw(boxes: List<Box>, width: Int, height: Int) {
        area.ensure(width, height)
        gl.bindFramebuffer(GL_FRAMEBUFFER, area.framebuffer)
        gl.viewport(0, 0, width, height)
        gl.clear()
        viewPasses.draw(boxes, width, height)
        val buffer = spareBuffers.removeFirstOrNull() ?: gl.createBuffer()
        gl.bindBuffer(GL_PIXEL_PACK_BUFFER, buffer)
        gl.bufferData(GL_PIXEL_PACK_BUFFER, width * height * 4, GL_STREAM_READ)
        gl.readPixelsIntoPackBuffer(0, 0, width, height)
        gl.bindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        reads.addLast(Read(buffer, gl.fenceSync(), width, height))
        gl.flush() // so that the fence reaches the GPU, and is signalled, without a buffer swap
    }

    /** How many areas drawn are still being read back. */
    val reading: Int get() = reads.size

    /**
     * The area drawn first of those being read back, RGBA with the top row first as images take it,
     * once the GPU has read it, else null; with [wait], it waits for the GPU instead.
     * GL reads the bottom row first.
     */
    @Suppress("ReturnCount")
    fun takeArea(wait: Boolean = false): ByteArray? {
        val read = reads.firstOrNull() ?: return null
        if (!wait && !gl.signalled(read.fence)) return null
        reads.removeFirst()
        gl.deleteSync(read.fence)
        val stride = read.width * 4
        val pixels = ByteArray(stride * read.height)
        gl.bindBuffer(GL_PIXEL_PACK_BUFFER, read.buffer)
        gl.readMapped(GL_PIXEL_PACK_BUFFER, pixels.size) { mapped ->
            for (row in 0 until read.height) mapped.get((read.height - 1 - row) * stride, pixels, row * stride, stride)
        }
        gl.bindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        spareBuffers.addLast(read.buffer)
        return pixels
    }

    /** Deletes the GL objects, in the context they were made in. */
    fun release() {
        viewPasses.release()
        area.release()
        gl.deleteTexture(raw)
        for (read in reads) {
            gl.deleteSync(read.fence)
            gl.deleteBuffer(read.buffer)
        }
        reads.clear()
        spareBuffers.forEach(gl::deleteBuffer)
        spareBuffers.clear()
    }
}
