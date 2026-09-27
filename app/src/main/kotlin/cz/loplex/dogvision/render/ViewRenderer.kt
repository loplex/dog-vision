package cz.loplex.dogvision.render

import android.opengl.GLES30.GL_COLOR_BUFFER_BIT
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.GL_RGBA
import android.opengl.GLES30.GL_RGBA8
import android.opengl.GLES30.GL_TEXTURE_2D
import android.opengl.GLES30.GL_UNSIGNED_BYTE
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glBindTexture
import android.opengl.GLES30.glClear
import android.opengl.GLES30.glClearColor
import android.opengl.GLES30.glTexImage2D
import android.opengl.GLES30.glViewport
import android.opengl.GLSurfaceView
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.ScreenLayout
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.layOut
import cz.loplex.dogvision.gl.ViewPasses
import cz.loplex.dogvision.gl.texture
import cz.loplex.dogvision.video.Recorder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Is handed the images of a frame drawn and the layout they were drawn in, on the GL thread. */
typealias OnImages = (List<Image>, ScreenLayout) -> Unit

/** Asks for the images of the next frame drawn. */
typealias Capture = (OnImages) -> Unit

/** What the renderer last drew: how the images were laid out, and the share of pixels that differ, if mapped. */
data class Drawn(val layout: ScreenLayout, val differenceShare: Double?)

/**
 * Draws the newest frame of [frames] as [view] asks, on the GL thread of a GLSurfaceView: the view's
 * images, rendered by [ViewPasses], scaled to the layout [layOut] gives.
 *
 * The frame and each image are a texture of the frame's size, which needs no check against the GPU's
 * GL_MAX_TEXTURE_SIZE: OpenGL ES 3.0 lets none be under 2048, and the camera's frames, a photo and a
 * video are shown no longer than that.
 */
class ViewRenderer(private val frames: FrameExchange, private val onDrawn: (Drawn) -> Unit) : GLSurfaceView.Renderer {
    /** What to show; set from any thread, and followed by GLSurfaceView.requestRender(). */
    @Volatile
    var view = View()

    /** The height of a caption under each image, and the gap between images, in screen pixels. */
    @Volatile
    var captionHeight = 0

    @Volatile
    var gap = 0

    /** Asks for another frame, as GLSurfaceView.requestRender() does; set before the first frame. */
    @Volatile
    var requestRender: () -> Unit = {}

    /** The recording to draw each frame into as well, set from any thread; null while there is none. */
    @Volatile
    var recorder: Recorder? = null

    private val passes = ViewPasses(Gles)
    private var recording: Recording? = null
    private var raw = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var shown: Frame? = null

    /**
     * The view the images were composed for last, and the share of their pixels that differs, counted
     * a frame or two after they were composed: the share of the map counted last.
     */
    private var composed: View? = null
    private var share: Double? = null
    private var lastDrawn: Drawn? = null
    private val captures = ConcurrentLinkedQueue<OnImages>()

    /**
     * Hands [onImages] the images of the next frame drawn, and the layout they were drawn in, on the
     * GL thread; follow it with GLSurfaceView.requestRender(). It is not called before a first frame.
     */
    fun capture(onImages: OnImages) {
        captures.add(onImages)
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        passes.lose()
        shown = null
        composed = null
        recording = null // its surface went with the context it was made in
        passes.create()
        raw = texture(Gles)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
    }

    override fun onDrawFrame(unused: GL10?) {
        val newest = frames.take() ?: frames.current().takeIf { shown == null }
        if (newest != null) {
            glBindTexture(GL_TEXTURE_2D, raw)
            glTexImage2D(
                GL_TEXTURE_2D, 0, GL_RGBA8, newest.width, newest.height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                newest.pixels.rewind(),
            )
            shown = newest
            passes.turnUpright(raw, newest.width, newest.height, newest.rotation, newest.mirrored)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, screenWidth, screenHeight)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        if (shown == null) return
        val view = view
        val upright = passes.frame
        val layout = layOut(screenWidth, screenHeight, upright.width, upright.height, view.images, captionHeight, gap)
        // A still photo is drawn again for every change of layout and every frame recorded; it is composed once.
        if (newest != null || view != composed) {
            passes.compose(view)
            composed = view
            if (view.sideBySide && view.difference) passes.countDifferences()
        }
        if (!view.sideBySide || !view.difference) {
            share = null
        } else if (passes.counting) {
            passes.takeDifferenceShare()?.let { share = it }
            // Drawn again until the count is handed over, which a still photo would not otherwise be.
            if (passes.counting) requestRender()
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        passes.draw(layout.images, screenWidth, screenHeight)
        while (true) {
            val capture = captures.poll() ?: break
            capture(readImages(), layout)
        }
        record(view, layout)
        val drawn = Drawn(layout, share)
        if (drawn != lastDrawn) {
            lastDrawn = drawn
            onDrawn(drawn)
        }
    }

    /** Draws the images into the recording [recorder] asks for, starting it with the first frame drawn. */
    private fun record(view: View, layout: ScreenLayout) {
        val recorder = recorder
        if (recording?.recorder !== recorder) {
            recording?.close()
            recording = null
        }
        if (recorder == null) return
        val recording = recording
            ?: Recording.open(recorder, view, layout.arrangement, passes.frame.width, passes.frame.height)
            ?: return
        this.recording = recording
        recording.draw(passes)
    }

    /**
     * The images of the view drawn last, read back from the GPU, left to right or top to bottom; call
     * it on the GL thread. The map's alpha, which marked what COUNT counts, is made opaque.
     */
    fun readImages(): List<Image> = passes.readImages(view.images)
}
