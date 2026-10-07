package cz.loplex.dogvision.ffmpeg

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * An ffmpeg encoder of video: its [name] as ffmpeg knows it, the [format] it writes, such as H.265,
 * the [options] that set its quality, or null for a hardware encoder, which is given a bitrate instead,
 * and the narrowest frame, in pixels, it is tried on, [minWidth].
 */
data class Encoder(val name: String, val format: String, val options: List<String>?, val minWidth: Int = 0)

/**
 * ffmpeg's encoders, best first: H.265 before H.264, software before hardware,
 * as the software encoders give the better picture for the size.
 */
val ENCODERS = listOf(
    // libx265 3.5 and 4.3 crash on videos 34 to 48 pixels wide, whatever their height;
    // 4.3 at random, in about a fourth of the runs, so that no probe of a few frames tells it.
    // Narrower ones encode, but lose nothing by H.264.
    Encoder("libx265", "H.265", listOf("-crf", "24", "-preset", "medium", "-x265-params", "log-level=error"), 50),
    Encoder("hevc_videotoolbox", "H.265", null), // macOS
    Encoder("hevc_nvenc", "H.265", null), // NVIDIA
    Encoder("hevc_qsv", "H.265", null), // Intel
    Encoder("hevc_amf", "H.265", null), // AMD, on Windows
    Encoder("libx264", "H.264", listOf("-crf", "20", "-preset", "medium")),
    Encoder("h264_videotoolbox", "H.264", null),
    Encoder("h264_nvenc", "H.264", null),
    Encoder("h264_qsv", "H.264", null),
    Encoder("h264_amf", "H.264", null),
    Encoder("mpeg4", "MPEG-4", listOf("-q:v", "3")),
)

/**
 * The frames are sRGB, whose primaries are BT.709's.
 * Encoding them with the BT.709 matrix, and saying so,
 * keeps players from reading them with BT.601's and shifting every colour.\
 * swscale's default fast rounding darkens every colour by up to 5 of 255;
 * accurate_rnd and full_chroma_int bring that to the 1 or 2 that 8-bit 4:2:0 costs anyway.\
 * H.265 and H.264 need even dimensions, which the padding provides.
 */
internal const val VIDEO_FILTER = "pad=ceil(iw/2)*2:ceil(ih/2)*2," +
    "scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p"

/** How a program run to its end went: its exit [status] and what it wrote to its standard output. */
data class Ran(val status: Int, val output: String)

/**
 * Runs a command of ffmpeg's to its end, within a number of seconds; null if it did not end in time.\
 * Throws [FfmpegMissing] if the program cannot be run.
 */
fun interface Runs {
    fun run(command: List<String>, timeoutSeconds: Long): Ran?
}

/** `command` run as [Runs] says, its program from where [FfmpegPrograms] runs it, and stopped if it takes too long. */
val runProgram = Runs { command, timeoutSeconds ->
    val process = FfmpegPrograms.start(command)
    val errors = drained(process)
    var output = ""
    val reader = thread(name = "ffmpeg-output", isDaemon = true) {
        output = process.inputStream.bufferedReader().readText()
    }
    if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        reader.join()
        errors.join()
        Ran(process.exitValue(), output)
    } else {
        stop(process)
        null
    }
}

/**
 * The first of [ENCODERS] whose [Encoder.minWidth] [width] reaches,
 * that ffmpeg lists and that encodes three frames of [width] by [height] here, or null if none does:
 * three, so that it encodes frames predicted from another, not a keyframe alone.
 * A listed encoder can still fail: a hardware one needs its hardware, and each has a largest size.\
 * Throws [FfmpegMissing] if ffmpeg cannot be run.
 */
fun bestEncoder(width: Int, height: Int, runs: Runs = runProgram): Encoder? {
    val listed = runs.run(listOf("ffmpeg", "-hide_banner", "-encoders"), LIST_SECONDS)?.output.orEmpty()
    return ENCODERS.firstOrNull { encoder ->
        @Suppress("ktlint:standard:argument-list-wrapping")
        val test = listOf(
            "ffmpeg", "-v", "error",
            "-f", "lavfi", "-i", "color=c=gray:s=${width}x$height:d=0.1",
            "-frames:v", "3",
            "-vf", VIDEO_FILTER,
            "-c:v", encoder.name,
        ) + (encoder.options ?: listOf("-b:v", "1M")) + listOf("-f", "null", "-")
        width >= encoder.minWidth && " ${encoder.name} " in listed && runs.run(test, PROBE_SECONDS)?.status == 0
    }
}

private const val LIST_SECONDS = 30L

/** How long the frames may take to encode before their encoder is passed over. */
private const val PROBE_SECONDS = 30L
