package cz.loplex.dogvision.ffmpeg

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToLong

/** Whether a video written from another carries its sound. */
enum class Sound {
    /** The original's sound is in it. */
    KEPT,

    /** The original has no sound. */
    NONE,
}

/**
 * An .mp4 at [output] of frames [width] by [height], encoded by [encoder] and tagged BT.709,
 * at [rate] frames a second as ffmpeg writes a rate, with the first sound track of [soundFrom],
 * if one is given, copied where an .mp4 holds its codec as it is, else re-encoded to AAC.
 *
 * A frame is 0xRRGGBB ints, a row after another, as core's images hold them.\
 * A failure of ffmpeg is an IOException with the last thing it said; [abort] stops it and removes the unfinished file.
 */
class VideoWriter private constructor(
    private val output: File,
    private val width: Int,
    private val height: Int,
    val encoder: Encoder,
    rate: String,
    soundFrom: SoundFrom?,
) {
    /** A video whose first sound track, of [codec], the video written carries. */
    private class SoundFrom(val file: File, val codec: String)

    /**
     * An .mp4 converted from the video [source], which ffprobe says is [stream]:
     * at its rate, with its first sound track.
     */
    constructor(output: File, source: File, stream: VideoStream, width: Int, height: Int, encoder: Encoder) : this(
        output,
        width,
        height,
        encoder,
        stream.rate,
        SoundFrom(source, stream.audio).takeIf { stream.audio.isNotEmpty() },
    )

    /** Whether the original's sound is carried over. */
    val sound = if (soundFrom == null) Sound.NONE else Sound.KEPT

    private val process: Process
    private val errors: Drained
    private val input: OutputStream
    private val bytes = ByteBuffer.allocate(width * height * 4)

    init {
        val quality = encoder.options
            ?: listOf("-b:v", (width * height * rateOf(rate) * BITS_PER_PIXEL).roundToLong().toString())
        // The tag Apple's players need to play H.265.
        val tag = if (encoder.format == "H.265") listOf("-tag:v", "hvc1") else emptyList()
        val sound = if (soundFrom == null) {
            listOf("-map", "0:v")
        } else {
            val codec = if (soundFrom.codec in MP4_AUDIO) "copy" else "aac"
            listOf("-i", soundFrom.file.path, "-map", "0:v", "-map", "1:a:0?", "-c:a", codec)
        }

        @Suppress("ktlint:standard:argument-list-wrapping")
        val command = listOf(
            "ffmpeg", "-v", "error", "-y",
            "-f", "rawvideo", "-pix_fmt", "0rgb", "-s", "${width}x$height", "-r", rate, "-i", "-",
        ) + sound + listOf("-vf", VIDEO_FILTER, "-c:v", encoder.name) + quality + tag + BT709_TAGS + listOf(
            "-movflags", "+faststart",
            output.path,
        )
        process = FfmpegPrograms.start(command, input = true)
        errors = drained(process)
        input = process.outputStream.buffered(bytes.capacity())
    }

    /** Encodes [pixels], a frame of the writer's size. */
    fun write(pixels: IntArray) {
        require(pixels.size == width * height) { "A frame of ${pixels.size} pixels, not $width x $height" }
        bytes.clear()
        bytes.asIntBuffer().put(pixels)
        try {
            input.write(bytes.array())
        } catch (_: IOException) {
            fail()
        }
    }

    /** Finishes the file, once every frame is written. */
    fun close() {
        try {
            input.close()
        } catch (_: IOException) {
            fail()
        }
        if (process.waitFor() != 0) fail()
        errors.join()
    }

    /** Stops writing and removes the unfinished file. */
    fun abort() {
        stop(process)
        errors.join()
        output.delete()
    }

    private fun fail(): Nothing {
        val status = process.waitFor()
        errors.join()
        throw IOException("ffmpeg (${encoder.name}) failed: ${errors.lastLine() ?: "status $status"}")
    }

    companion object {
        /** A video of frames written at [framesPerSecond], with no sound, as a recording of the view is. */
        fun silent(output: File, width: Int, height: Int, encoder: Encoder, framesPerSecond: Int) =
            VideoWriter(output, width, height, encoder, "$framesPerSecond", null)

        /** Of every frame, for the encoders without a quality target. */
        private const val BITS_PER_PIXEL = 0.1

        private val BT709_TAGS = listOf("-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709")

        /** Audio codecs an .mp4 holds as they are; any other sound is re-encoded to AAC. */
        private val MP4_AUDIO = setOf("aac", "mp3", "ac3", "eac3", "opus", "flac", "alac")
    }
}
