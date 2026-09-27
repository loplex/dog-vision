package cz.loplex.dogvision.video

import android.content.Context
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.GL_FRAMEBUFFER_BINDING
import android.opengl.GLES30.GL_MAX_TEXTURE_SIZE
import android.opengl.GLES30.GL_VERSION
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glGetIntegerv
import android.opengl.GLES30.glGetString
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.Box
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.gl.ViewPasses
import cz.loplex.dogvision.render.Gles

/**
 * A Media3 effect that turns each frame of a video into [view] of it, its images side by side or
 * one above another as the view's arrangement says: the frames a conversion writes.
 *
 * The view is drawn into one texture, which cannot be larger than the GPU's GL_MAX_TEXTURE_SIZE, or
 * [textureLimit] if that is smaller; a view larger than that, as three images of a 4K video side by side
 * are, is scaled down to fit it, keeping its shape. Each image is still rendered at the frame's size.
 *
 * It renders with the passes the screen renders with, which need OpenGL ES 3.0, and takes 8-bit SDR
 * frames only, as a conversion that tone-maps HDR to SDR hands them over. Media3 keeps a frame's
 * first row at the top of its texture, t = 1, where the passes keep it at t = 0, so the frame is
 * flipped on the way in, and [ViewPasses.draw] flips the images back on the way out.
 */
@OptIn(UnstableApi::class)
class ViewEffect(private val view: View, private val textureLimit: Int = Int.MAX_VALUE) : GlEffect {
    /** The size of the view of the frames it takes, once it has been told it, before it is scaled down to fit. */
    @Volatile
    var renderedSize: Pair<Int, Int>? = null
        private set

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("The view is rendered from SDR frames only")
        return Program()
    }

    private inner class Program : BaseGlShaderProgram(false, 1) {
        private var passes: ViewPasses? = null
        private var inputWidth = 0
        private var inputHeight = 0
        private var boxes = emptyList<Box>()
        private var outputWidth = 0
        private var outputHeight = 0

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (passes == null) {
                val version = glGetString(GL_VERSION).orEmpty()
                if (!version.startsWith("OpenGL ES 3")) {
                    throw VideoFrameProcessingException("The view needs OpenGL ES 3.0, and the context is $version")
                }
                passes = ViewPasses(Gles).apply { create() }
            }
            this.inputWidth = inputWidth
            this.inputHeight = inputHeight
            val (width, height) = composedSize(view, inputWidth, inputHeight)
            renderedSize = width to height
            val gpuLimit = IntArray(1).also { glGetIntegerv(GL_MAX_TEXTURE_SIZE, it, 0) }[0]
            val limit = minOf(gpuLimit, textureLimit)
            val scale = minOf(1.0, limit.toDouble() / width, limit.toDouble() / height)
            // Each image scaled down to an even size, which every encoder takes, so that the images tile the view.
            val imageWidth = if (scale < 1) (inputWidth * scale).toInt() and 1.inv() else inputWidth
            val imageHeight = if (scale < 1) (inputHeight * scale).toInt() and 1.inv() else inputHeight
            val (scaledWidth, scaledHeight) = composedSize(view, imageWidth, imageHeight)
            outputWidth = scaledWidth
            outputHeight = scaledHeight
            boxes = List(view.images) { i ->
                if (view.arrangement == Arrangement.ROW) {
                    Box(i * imageWidth, 0, imageWidth, imageHeight)
                } else {
                    Box(0, i * imageHeight, imageWidth, imageHeight)
                }
            }
            return Size(scaledWidth, scaledHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val passes = checkNotNull(passes)
            val output = IntArray(1)
            glGetIntegerv(GL_FRAMEBUFFER_BINDING, output, 0)
            // Half a turn and a mirror are a flip from top to bottom.
            passes.turnUpright(inputTexId, inputWidth, inputHeight, rotation = 180, mirrored = true)
            passes.compose(view)
            glBindFramebuffer(GL_FRAMEBUFFER, output[0])
            passes.draw(boxes, outputWidth, outputHeight)
        }

        override fun release() {
            super.release()
            passes?.release()
            passes = null
        }
    }
}
