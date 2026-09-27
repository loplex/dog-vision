package cz.loplex.dogvision.render

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30.GL_COLOR_BUFFER_BIT
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glClear
import android.opengl.GLES30.glClearColor
import android.opengl.GLES30.glViewport
import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.Box
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.gl.ViewPasses
import cz.loplex.dogvision.video.RECORDING_FPS
import cz.loplex.dogvision.video.Recorder
import java.io.IOException

/**
 * The renderer's side of a [Recorder]: a surface on the encoder's input in the renderer's EGL context,
 * which the view's images are drawn into as they are drawn to the screen.
 *
 * The recording's frames lie on a grid of [RECORDING_FPS] a second. A frame drawn is recorded at the
 * point of the grid it falls after, unless that point has a frame already; a frame not recorded
 * leaves the one before it shown longer, and so does a point no frame fell after. The images keep
 * the arrangement they had when the recording started, whatever the screen's layout does after.
 */
internal class Recording private constructor(
    val recorder: Recorder,
    private val surface: EGLSurface,
    private val boxes: List<Box>,
    private val width: Int,
    private val height: Int,
) {
    private val display = EGL14.eglGetCurrentDisplay()
    private val context = EGL14.eglGetCurrentContext()
    private var started = 0L
    private var lastFrame = -1L

    /** Draws the images [passes] composed last into the recording, if a frame of it is due. */
    fun draw(passes: ViewPasses) {
        if (!recorder.accepting) return
        val now = System.nanoTime()
        if (lastFrame < 0) started = now
        val frame = (now - started) * RECORDING_FPS / 1_000_000_000L
        if (frame <= lastFrame) return
        lastFrame = frame
        val drawing = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val reading = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glViewport(0, 0, width, height)
            glClearColor(0f, 0f, 0f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)
            passes.draw(boxes, width, height)
            EGLExt.eglPresentationTimeANDROID(display, surface, frame * 1_000_000_000L / RECORDING_FPS)
            // Fails once the encoder has stopped taking frames, which the recorder already knows.
            EGL14.eglSwapBuffers(display, surface)
        } finally {
            EGL14.eglMakeCurrent(display, drawing, reading, context)
        }
    }

    /** Lets go of the encoder's input; the recorder itself is stopped by whoever started it. */
    fun close() {
        EGL14.eglDestroySurface(display, surface)
    }

    companion object {
        /**
         * Starts [recorder] for [view] of an upright frame of [frameWidth] x [frameHeight], its images
         * arranged as [arrangement] says, in the EGL context current on this thread; null if it fails,
         * which the recorder then tells.
         */
        fun open(
            recorder: Recorder,
            view: View,
            arrangement: Arrangement,
            frameWidth: Int,
            frameHeight: Int,
        ): Recording? {
            val (viewWidth, viewHeight) = composedSize(view.copy(arrangement = arrangement), frameWidth, frameHeight)
            recorder.start(viewWidth, viewHeight)
            val input = recorder.surface ?: return null
            val (width, height) = recorder.size ?: return null
            val display = EGL14.eglGetCurrentDisplay()
            val none = intArrayOf(EGL14.EGL_NONE)
            val surface = EGL14.eglCreateWindowSurface(display, currentConfig(), input, none, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                recorder.fail(IOException("Cannot draw into the encoder: EGL error ${EGL14.eglGetError()}"))
                return null
            }
            val count = view.images
            val boxes = List(count) { i ->
                if (arrangement == Arrangement.ROW) {
                    Box(i * width / count, 0, (i + 1) * width / count - i * width / count, height)
                } else {
                    Box(0, i * height / count, width, (i + 1) * height / count - i * height / count)
                }
            }
            return Recording(recorder, surface, boxes, width, height)
        }
    }
}
