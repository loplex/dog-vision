package cz.loplex.dogvision.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES30.GL_CLAMP_TO_EDGE
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.GL_LINEAR
import android.opengl.GLES30.GL_RGBA
import android.opengl.GLES30.GL_TEXTURE0
import android.opengl.GLES30.GL_TEXTURE_MAG_FILTER
import android.opengl.GLES30.GL_TEXTURE_MIN_FILTER
import android.opengl.GLES30.GL_TEXTURE_WRAP_S
import android.opengl.GLES30.GL_TEXTURE_WRAP_T
import android.opengl.GLES30.GL_TRIANGLES
import android.opengl.GLES30.GL_UNSIGNED_BYTE
import android.opengl.GLES30.glActiveTexture
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glBindTexture
import android.opengl.GLES30.glBindVertexArray
import android.opengl.GLES30.glDeleteTextures
import android.opengl.GLES30.glDeleteVertexArrays
import android.opengl.GLES30.glDrawArrays
import android.opengl.GLES30.glGenTextures
import android.opengl.GLES30.glGenVertexArrays
import android.opengl.GLES30.glReadPixels
import android.opengl.GLES30.glTexParameteri
import android.opengl.GLES30.glUniform1i
import android.opengl.GLES30.glUniform2f
import android.opengl.GLES30.glUniformMatrix4fv
import android.opengl.GLES30.glViewport
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import cz.loplex.dogvision.gl.Program
import cz.loplex.dogvision.gl.Shaders
import cz.loplex.dogvision.gl.Target
import cz.loplex.dogvision.render.FrameExchange
import cz.loplex.dogvision.render.Gles
import cz.loplex.dogvision.render.OffscreenContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The frames of the video at [uri], played at its own rate and over again from its start, without
 * its sound, handed to [frames] as RGBA no larger than [longestSide] while it plays.
 *
 * ExoPlayer decodes it into a SurfaceTexture, which a GL context on a thread of the feed's own
 * scales down and reads back, so that the renderer takes a video's frames as it takes the camera's.
 * The player is made and told what to do on the thread that makes the feed, which must have a Looper.
 *
 * An HDR video is played by Media3's CompositionPlayer instead, which tone-maps it to SDR with
 * OpenGL as a conversion does, so that it looks as it will once saved. ExoPlayer tells which a video
 * is; its frames are held back until then. A GPU that cannot tone-map, lacking GL_EXT_YUV_target,
 * gets the video from ExoPlayer again, its HDR frames taken as SDR ones.
 */
class VideoFeed(
    private val context: Context,
    private val uri: Uri,
    private val frames: FrameExchange,
    private val longestSide: Int,
    private val onError: (Throwable) -> Unit,
) {
    private val generation = frames.open()
    private val thread = HandlerThread("VideoFeed").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.myLooper() ?: error("A VideoFeed is made on a thread with a Looper"))
    private val surfaceTexture = SurfaceTexture(false)
    private val surface = Surface(surfaceTexture)

    /** The size of the frames the player renders, as they are shown, once it knows it. */
    @Volatile
    private var size: VideoSize? = null

    /** Whether the frames coming are the ones to show: not while it is not known yet whether the video is HDR. */
    @Volatile
    private var deciding = true

    private var gl: Gl? = null

    /** Whether an HDR video is tone-mapped; not once the GPU failed to. */
    private var toneMapping = true

    private var playing = false

    private var player: Player = plainPlayer()

    init {
        handler.post {
            try {
                gl = Gl()
            } catch (error: RuntimeException) {
                main.post { onError(error) }
            }
        }
        surfaceTexture.setOnFrameAvailableListener({ deliver() }, handler)
    }

    fun play() {
        playing = true
        player.play()
    }

    fun pause() {
        playing = false
        player.pause()
    }

    /** Stops the video for good; the feed cannot be used after. */
    fun release() {
        player.release()
        handler.post {
            gl?.release()
            gl = null
            surface.release()
            surfaceTexture.release()
            thread.quitSafely()
        }
    }

    /**
     * ExoPlayer, playing the video as its decoder hands it over, and telling whether it is HDR; the
     * format it tells that by is unstable API.
     */
    @OptIn(UnstableApi::class)
    private fun plainPlayer(): Player = playerWithDecoderFallback(context).apply {
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        repeatMode = Player.REPEAT_MODE_ONE
        addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } ?: return
                val format = (0 until video.length).firstOrNull(video::isTrackSelected)?.let(video::getTrackFormat)
                if (deciding && toneMapping && format != null && isHdr(format) && duration != C.TIME_UNSET) {
                    toneMap(format, durationUs = duration * 1000)
                } else {
                    deciding = false
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) size = videoSize
            }

            override fun onPlayerError(error: PlaybackException) = onError(error)
        })
        setVideoSurface(surface)
        setMediaItem(MediaItem.fromUri(uri))
        prepare()
        if (playing) play()
    }

    /**
     * Plays the HDR video of [format], [durationUs] long, through a CompositionPlayer instead,
     * tone-mapped to SDR and scaled to fit [longestSide] as it is shown; if that fails, through
     * ExoPlayer again.
     */
    @OptIn(UnstableApi::class)
    private fun toneMap(format: Format, durationUs: Long) {
        val shownWidth = format.width * format.pixelWidthHeightRatio
        val shownHeight = format.height.toFloat()
        val turned = format.rotationDegrees % 180 != 0
        val width = if (turned) shownHeight else shownWidth
        val height = if (turned) shownWidth else shownHeight
        val scale = minOf(1f, longestSide / max(width, height))
        val outWidth = max(2, (width * scale).roundToInt())
        val outHeight = max(2, (height * scale).roundToInt())
        player.release()
        size = VideoSize(outWidth, outHeight)
        surfaceTexture.setDefaultBufferSize(outWidth, outHeight)
        // CompositionPlayer checks what it is given by throwing, as it does for a duration it cannot use.
        val toneMapped = try {
            toneMappingPlayer(context, uri, durationUs, outWidth, outHeight, surface)
        } catch (error: RuntimeException) {
            null
        }
        if (toneMapped == null) {
            playPlainly()
            return
        }
        player = toneMapped.apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    player.release()
                    playPlainly()
                }
            })
            if (playing) play()
        }
        deciding = false
    }

    /** Plays the video through ExoPlayer again, its HDR frames taken as SDR ones, once tone-mapping it failed. */
    private fun playPlainly() {
        toneMapping = false
        deciding = true
        size = null
        player = plainPlayer()
    }

    /**
     * On the feed's thread: the newest frame the player rendered, scaled down, to [frames]. It is
     * latched even when it is not delivered, which frees its buffer for the player's next.
     */
    private fun deliver() {
        val gl = gl ?: return
        try {
            gl.latch()
            if (deciding) return
            val video = size ?: return
            val shownWidth = video.width * video.pixelWidthHeightRatio
            val scale = minOf(1f, longestSide / max(shownWidth, video.height.toFloat()))
            val width = max(1, (shownWidth * scale).roundToInt())
            val height = max(1, (video.height * scale).roundToInt())
            val frame = frames.obtain(width, height) ?: return
            gl.draw(width, height, taps = ceil(1 / scale).toInt())
            frame.pixels.clear()
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, frame.pixels)
            frame.rotation = 0
            frame.mirrored = false
            frames.publish(frame, generation)
        } catch (error: RuntimeException) {
            main.post { onError(error) }
        }
    }

    /** The GL objects of the feed's thread, with the SurfaceTexture attached to its context. */
    private inner class Gl {
        private val context = OffscreenContext()
        private val program = Program(Gles, Shaders.FULL_VIEWPORT, VIDEO_FRAME)
        private val target = Target(Gles)
        private val external: Int
        private val vertexArray: Int
        private val transform = FloatArray(16)

        init {
            val id = IntArray(1)
            glGenTextures(1, id, 0)
            external = id[0]
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, external)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            surfaceTexture.attachToGLContext(external)
            glGenVertexArrays(1, id, 0)
            vertexArray = id[0]
        }

        /** Makes the newest frame the player rendered the texture's. */
        fun latch() {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(transform)
        }

        /** Draws the frame latched scaled into the target, which is left bound for reading. */
        fun draw(width: Int, height: Int, taps: Int) {
            target.ensure(width, height)
            glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer)
            glViewport(0, 0, width, height)
            glBindVertexArray(vertexArray)
            program.use()
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, external)
            glUniform1i(program.uniform("uVideo"), 0)
            glUniformMatrix4fv(program.uniform("uTransform"), 1, false, transform, 0)
            glUniform2f(program.uniform("uSize"), width.toFloat(), height.toFloat())
            glUniform1i(program.uniform("uTaps"), taps)
            glDrawArrays(GL_TRIANGLES, 0, 3)
        }

        fun release() {
            surfaceTexture.detachFromGLContext()
            target.release()
            program.release()
            glDeleteTextures(1, intArrayOf(external), 0)
            glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
            context.release()
        }
    }
}

/**
 * A player that tries the next decoder for a video that the first refuses, as a hardware decoder
 * refuses one below a size of its own; the flag, and the builder taking it, are unstable API.
 */
@OptIn(UnstableApi::class)
private fun playerWithDecoderFallback(context: Context): ExoPlayer =
    ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true)).build()

/** Whether a video of [format] is HDR: encoded with PQ, as HDR10 and Dolby Vision are, or with HLG. */
@OptIn(UnstableApi::class)
private fun isHdr(format: Format): Boolean =
    format.colorInfo?.colorTransfer.let { it == C.COLOR_TRANSFER_ST2084 || it == C.COLOR_TRANSFER_HLG }

/**
 * A player of the video at [uri], [durationUs] long, without its sound, tone-mapped to SDR with
 * OpenGL as a conversion does, into [surface] at [width] x [height]. CompositionPlayer is
 * experimental API, its builders unstable.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
private fun toneMappingPlayer(
    context: Context,
    uri: Uri,
    durationUs: Long,
    width: Int,
    height: Int,
    surface: Surface,
): Player {
    val fit = Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
    val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
        .setDurationUs(durationUs)
        .setEffects(Effects(emptyList(), listOf(fit)))
        .build()
    val composition = Composition.Builder(EditedMediaItemSequence.withVideoFrom(listOf(item)))
        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        .build()
    val player = CompositionPlayer.Builder(context).build()
    try {
        player.setComposition(composition)
        player.setVideoSurface(surface, Size(width, height))
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.prepare()
    } catch (error: RuntimeException) {
        player.release()
        throw error
    }
    return player
}

/**
 * A video's frame, from the external texture of a SurfaceTexture, scaled to the target: each
 * pixel is the mean of uTaps x uTaps samples spread over the part of the frame it covers, so
 * that a frame scaled down to a third of its size does not alias. The frame's first row is
 * written to the target's first row, as a Frame holds it.
 */
private const val VIDEO_FRAME = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uVideo;
uniform mat4 uTransform; // the SurfaceTexture's, from the frame upright to its buffer
uniform vec2 uSize; // of the target
uniform int uTaps;
out vec4 outColour;

void main() {
    vec4 sum = vec4(0.0);
    for (int j = 0; j < uTaps; j++) for (int i = 0; i < uTaps; i++) {
        vec2 at = (floor(gl_FragCoord.xy) + (vec2(i, j) + 0.5) / float(uTaps)) / uSize;
        sum += texture(uVideo, (uTransform * vec4(at.x, 1.0 - at.y, 0.0, 1.0)).xy);
    }
    outColour = vec4(sum.rgb / float(uTaps * uTaps), 1.0);
}
"""
