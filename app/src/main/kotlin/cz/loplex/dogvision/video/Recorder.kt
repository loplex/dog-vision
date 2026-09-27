package cz.loplex.dogvision.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/** Frames a second a recording is written at, whatever rate the view is drawn at, as the desktop's is. */
const val RECORDING_FPS = 30

/** Bits of every frame's pixel a recording's encoder is given, as the desktop gives a hardware encoder. */
private const val BITS_PER_PIXEL = 0.1

/**
 * A recording of the view to [output], an .mp4 without sound, which the renderer draws frames into.
 *
 * The renderer calls [start] with the size of the view once it draws the first frame, draws each
 * frame into [surface] on a grid of [RECORDING_FPS] frames a second, a frame repeated for as long as
 * it is shown, and stops drawing once [accepting] turns false. [stop] ends the video from any
 * thread; the encoder's last frames are written on a thread of the recorder's own, and [onFinished]
 * is then told how the video was written, or why it could not be, on the main thread.
 */
class Recorder(private val output: File, private val onFinished: (Result<Written>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("Recorder").apply { start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var written: Written? = null
    private var finished = false

    /** The encoder's input, once started. */
    var surface: Surface? = null
        private set

    /** The size the video is written at, once started: the view's, or smaller if the encoder needs it. */
    var size: Pair<Int, Int>? = null
        private set

    /** Whether frames drawn into [surface] are recorded: from [start] until [stop]. */
    @Volatile
    var accepting = false
        private set

    @Volatile
    private var stopped = false

    /**
     * Starts the encoder for a view of [width] x [height], on the GL thread; H.265 where the device
     * has a hardware encoder for it, H.264 where not. The recording fails, and [onFinished] says so, if
     * no encoder takes it.
     */
    @Synchronized
    fun start(width: Int, height: Int) {
        if (stopped || finished || codec != null) return
        try {
            val (info, mimeType, encoded) = chooseEncoder(width, height)
            val format = MediaFormat.createVideoFormat(mimeType, encoded.first, encoded.second).apply {
                // chooseEncoder picks only an encoder with video capabilities.
                val bitRates = checkNotNull(info.getCapabilitiesForType(mimeType).videoCapabilities).bitrateRange
                val pixels = encoded.first.toDouble() * encoded.second
                val bitRate = (pixels * RECORDING_FPS * BITS_PER_PIXEL).roundToInt()
                setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRates.clamp(bitRate))
                setInteger(MediaFormat.KEY_FRAME_RATE, RECORDING_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // The frames are sRGB, whose primaries are BT.709's; saying so keeps players from
                // shifting every colour.
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val codec = MediaCodec.createByCodecName(info.name).also { codec = it }
            codec.setCallback(Callback(), handler)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            val scaled = encoded.first * encoded.second < width * height
            written = Written(formatName(mimeType), info.name, sound = false, encoded.takeIf { scaled }, silent = true)
            size = encoded
            accepting = true
        } catch (error: IOException) {
            fail(error)
        } catch (error: IllegalStateException) { // MediaCodec.CodecException among them
            fail(error)
        } catch (error: IllegalArgumentException) {
            fail(error)
        }
    }

    /** Ends the video; frames drawn after it are not recorded. */
    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        accepting = false
        val codec = codec
        when {
            finished -> Unit

            codec == null -> fail(IOException("No frame was shown while recording"))

            else -> handler.post {
                try {
                    codec.signalEndOfInputStream()
                } catch (error: IllegalStateException) {
                    fail(error)
                }
            }
        }
    }

    private inner class Callback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // fed through the surface

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val muxer = checkNotNull(muxer)
            track = muxer.addTrack(format)
            muxer.start()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buffer = codec.getOutputBuffer(index)
                // The configuration is in the format the muxer was given, so it is not written as a frame.
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (buffer != null && info.size > 0 && !isConfig && track >= 0) {
                    checkNotNull(muxer).writeSampleData(track, buffer, info)
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) finish()
            } catch (error: IllegalStateException) {
                fail(error)
            }
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) = fail(error)
    }

    /** On the recorder's thread, after the encoder's last frame: closes the file. */
    @Synchronized
    private fun finish() {
        if (finished) return
        try {
            if (track < 0) throw IOException("No frame was shown while recording")
            checkNotNull(muxer).stop()
            release()
            val written = checkNotNull(written)
            main.post { onFinished(Result.success(written)) }
        } catch (error: IOException) {
            fail(error)
        } catch (error: IllegalStateException) {
            fail(error)
        }
    }

    /** Gives up, from any thread: releases what there is, removes the unfinished file, and says why. */
    @Synchronized
    fun fail(error: Exception) {
        if (finished) return
        accepting = false
        release()
        output.delete()
        main.post { onFinished(Result.failure(error)) }
    }

    private fun release() {
        finished = true
        runCatching { codec?.stop() }
        codec?.release()
        codec = null
        runCatching { muxer?.release() }
        muxer = null
        surface?.release()
        thread.quitSafely()
    }
}

/**
 * An encoder that takes frames from a surface, and the size it takes of [width] x [height]: that one,
 * or the largest of the same shape it does. Hardware encoders come first, as only they keep up with
 * the view, and among them H.265 before H.264.
 */
private fun chooseEncoder(width: Int, height: Int): Triple<MediaCodecInfo, String, Pair<Int, Int>> {
    val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
    val candidates = listOf(true, false).flatMap { hardware ->
        listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC).flatMap { mimeType ->
            infos.filter { info ->
                isHardware(info) == hardware && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }.map { it to mimeType }
        }
    }
    for ((info, mimeType) in candidates) {
        val capabilities = info.getCapabilitiesForType(mimeType)
        if (COLOR_FormatSurface !in capabilities.colorFormats) continue
        val size = fit(capabilities.videoCapabilities ?: continue, width, height) ?: continue
        return Triple(info, mimeType, size)
    }
    throw IOException("No encoder for H.265 or H.264 takes a video of $width x $height")
}

private fun isHardware(info: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    info.isHardwareAccelerated
} else {
    !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
}

/** The largest size of the shape of [width] x [height], no larger, that [capabilities] take at the recording's rate. */
private fun fit(capabilities: MediaCodecInfo.VideoCapabilities, width: Int, height: Int): Pair<Int, Int>? {
    var scale = minOf(
        1.0,
        capabilities.supportedWidths.upper.toDouble() / width,
        capabilities.supportedHeights.upper.toDouble() / height,
    )
    repeat(40) {
        val w = align(width * scale, capabilities.widthAlignment)
        val h = align(height * scale, capabilities.heightAlignment)
        if (capabilities.areSizeAndRateSupported(w, h, RECORDING_FPS.toDouble())) return w to h
        scale *= 0.95
    }
    return null
}

/** [length] rounded down to an even multiple of [alignment], one at least. */
private fun align(length: Double, alignment: Int): Int {
    val step = if (alignment % 2 == 0) alignment else alignment * 2
    return maxOf(step, (length / step).toInt() * step)
}
