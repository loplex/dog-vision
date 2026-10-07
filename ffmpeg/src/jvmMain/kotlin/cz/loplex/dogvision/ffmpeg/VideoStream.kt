package cz.loplex.dogvision.ffmpeg

import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What ffprobe says of a video file: the [width] and [height] of its first video stream upright,
 * as ffmpeg turns its frames, its average frame [rate] as ffmpeg writes it, such as 30000/1001,
 * how many [frames] it has, or about as many, and the codec of its first sound track, [audio], "" where it has none.
 */
data class VideoStream(val width: Int, val height: Int, val rate: String, val frames: Long, val audio: String) {
    /** The frame rate as a number. */
    val framesPerSecond: Double get() = rateOf(rate)

    companion object {
        /** The rate a video is read at where its file gives none. */
        const val DEFAULT_RATE = "30"

        /**
         * What ffprobe says of [file].\
         * Throws [FfmpegMissing] if ffprobe cannot be run,
         * and IOException with what ffprobe says if the file holds no video it can read.
         */
        fun probe(file: File): VideoStream {
            val video = entries(
                file,
                "v:0",
                "stream=width,height,avg_frame_rate,r_frame_rate,nb_frames,duration:stream_side_data=rotation:" +
                    "format=duration",
            )
            val width = video["width"]?.toIntOrNull()?.takeIf { it > 0 }
            val height = video["height"]?.toIntOrNull()?.takeIf { it > 0 }
            if (width == null || height == null) throw IOException("${file.name} has no video")
            val rate = listOf(video["avg_frame_rate"], video["r_frame_rate"])
                .firstOrNull { it != null && rateOf(it) > 0 } ?: DEFAULT_RATE
            val duration = video["duration"]?.toDoubleOrNull()
            val frames = video["nb_frames"]?.toLongOrNull()?.takeIf { it > 0 }
                ?: duration?.let { (it * rateOf(rate)).roundToLong() }
                ?: 0
            val audio = entries(file, "a:0", "stream=codec_name")["codec_name"].orEmpty()

            @Suppress("MagicNumber")
            val quarter = video["rotation"]?.toDoubleOrNull()?.let { abs(it.toInt()) % 180 == 90 } ?: false
            return VideoStream(if (quarter) height else width, if (quarter) width else height, rate, frames, audio)
        }

        /**
         * The entries ffprobe shows of [file]'s stream [stream], as `key=value` lines, the last of a key kept;
         * empty where the file has no such stream.
         */
        private fun entries(file: File, stream: String, show: String): Map<String, String> {
            @Suppress("ktlint:standard:argument-list-wrapping")
            val command = listOf(
                "ffprobe", "-v", "error",
                "-select_streams", stream,
                "-show_entries", show,
                "-of", "default=noprint_wrappers=1",
                file.path,
            )
            val process = FfmpegPrograms.start(command)
            val errors = drained(process)
            val output = process.inputStream.bufferedReader().readText()
            val status = process.waitFor()
            errors.join()
            if (status != 0) throw IOException(errors.lastLine() ?: "ffprobe ended with status $status")
            return output.lines().mapNotNull { line ->
                val (key, value) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                (key.trim() to value.trim()).takeIf { it.second != "N/A" }
            }.toMap()
        }
    }
}

/** A rate as ffmpeg writes one, such as 30000/1001 or 25, as a number; 0 where it is none, as 0/0. */
internal fun rateOf(rate: String): Double {
    val parts = rate.split('/')
    val numerator = parts[0].toDoubleOrNull() ?: return 0.0
    val denominator = if (parts.size > 1) parts[1].toDoubleOrNull() ?: 0.0 else 1.0
    return if (denominator > 0) numerator / denominator else 0.0
}
