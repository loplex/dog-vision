package cz.loplex.dogvision.ffmpeg

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The frames of [file]'s first video stream, which ffprobe says is [stream],
 * decoded by ffmpeg upright and at full size, at the stream's average rate:
 * a video of a varying rate has frames repeated or dropped, so that its sound stays in step.\
 * A frame is 0xFFRRGGBB ints, a row after another, as core's images hold them.
 *
 * ffmpeg takes the colours by the matrix the video is tagged with;
 * its default fast rounding shifted them by up to 4 of 255 in ffmpeg 6.1,
 * which accurate_rnd and full_chroma_int bring to 1, as in the writer's filter.
 */
class VideoReader(file: File, private val stream: VideoStream) : AutoCloseable {
    @Suppress("ktlint:standard:argument-list-wrapping")
    private val process = FfmpegPrograms.start(
        listOf(
            "ffmpeg", "-v", "error", "-nostdin",
            "-i", file.path,
            "-map", "0:v:0", "-an", "-sn", "-dn",
            "-r", stream.rate, "-sws_flags", "accurate_rnd+full_chroma_int",
            "-pix_fmt", "argb", "-f", "rawvideo", "-",
        ),
    )
    private val errors = drained(process)
    private val bytes = ByteArray(stream.width * stream.height * 4)

    /**
     * Reads the next frame into [pixels], of the stream's size; false at the video's end.\
     * Throws IOException with what ffmpeg said if it could not decode the video.
     */
    fun read(pixels: IntArray): Boolean {
        require(pixels.size == stream.width * stream.height) { "${pixels.size} pixels for a frame" }
        val input = process.inputStream
        var filled = 0
        while (filled < bytes.size) {
            // In pieces, as the window's feed reads: under Wine a whole frame at once reads slower.
            val read = input.read(bytes, filled, minOf(READ_SIZE, bytes.size - filled))
            if (read < 0) break
            filled += read
        }
        if (filled < bytes.size) {
            val status = process.waitFor()
            errors.join()
            if (status != 0) throw IOException(errors.lastLine() ?: "ffmpeg ended with status $status")
            if (filled > 0) throw IOException("The video's last frame is cut short")
            return false
        }
        ByteBuffer.wrap(bytes).asIntBuffer().get(pixels)
        return true
    }

    /** Stops decoding, where the video is not read to its end. */
    override fun close() {
        if (process.isAlive) stop(process)
        errors.join()
    }

    private companion object {
        const val READ_SIZE = 64 * 1024
    }
}
