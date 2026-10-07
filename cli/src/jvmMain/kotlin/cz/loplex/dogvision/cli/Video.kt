package cz.loplex.dogvision.cli

import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.core.meanLinearRgb
import cz.loplex.dogvision.ffmpeg.Encoder
import cz.loplex.dogvision.ffmpeg.FfmpegMissing
import cz.loplex.dogvision.ffmpeg.Sound
import cz.loplex.dogvision.ffmpeg.VideoReader
import cz.loplex.dogvision.ffmpeg.VideoStream
import cz.loplex.dogvision.ffmpeg.VideoWriter
import cz.loplex.dogvision.ffmpeg.bestEncoder
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.io.File
import java.io.IOException
import java.io.PrintStream
import kotlin.math.roundToInt

/**
 * Converts the video [Arguments.file], which is no photo, to the view the arguments ask for, at full size:
 * each frame by core, as a photo is, the scene's mean taken again for each,
 * into an .mp4 of the best encoder ffmpeg has here, with the original's sound.\
 * Reports on [out] what it wrote, or on [err] why it could not,
 * and on [err] how far it is where [err] is a [terminal]; returns the exit status.\
 * Interrupted, as by Ctrl+C, it removes the unfinished file.
 */
@Suppress("ReturnCount")
fun convertVideo(arguments: Arguments, texts: Texts, out: PrintStream, err: PrintStream, terminal: Boolean): Int {
    val file = checkNotNull(arguments.file) { "No file to convert" }
    val view = arguments.view.conversionView
    val (stream, encoder) = prepare(file, view, texts, err) ?: return 1
    val (width, height) = composedSize(view, stream.width, stream.height)
    val output = convertedFile(file, view, arguments.outputDir, "mp4")
    output.parentFile?.mkdirs()
    val reader = VideoReader(file, stream)
    val writer = try {
        VideoWriter(output, file, stream, width, height, encoder)
    } catch (error: IOException) {
        reader.close()
        err.println(texts.get(Str.PHOTO_UNWRITABLE, output, error.message))
        return 1
    }
    val interrupted = Thread {
        reader.close()
        writer.abort()
    }
    Runtime.getRuntime().addShutdownHook(interrupted)
    val progress = Progress(texts, err, terminal, stream.frames)
    val frames = try {
        convertFrames(reader, writer, view, stream, progress)
    } catch (error: IOException) {
        writer.abort()
        progress.end()
        err.println(texts.get(Str.CONVERSION_FAILED, error.message))
        return 1
    } finally {
        reader.close()
        Runtime.getRuntime().removeShutdownHook(interrupted)
    }
    progress.end()
    if (frames == 0L) {
        err.println(texts.get(Str.VIDEO_NO_FRAMES, file))
        return 1
    }
    val written = if (writer.sound == Sound.KEPT) Str.WRITTEN_WITH_SOUND else Str.WRITTEN_WITHOUT_SOUND
    out.println(texts.get(Str.VIDEO_WRITTEN, output, texts.get(written, encoder.format, encoder.name)))
    return 0
}

/**
 * What ffprobe says of the video [file], and the encoder that writes it converted to [view]; null, said on [err],
 * where the file is no video, ffmpeg cannot be run, or none of its encoders writes a video of that size.
 */
private fun prepare(file: File, view: View, texts: Texts, err: PrintStream): Pair<VideoStream, Encoder>? = try {
    val stream = VideoStream.probe(file)
    val (width, height) = composedSize(view, stream.width, stream.height)
    val encoder = bestEncoder(width, height)
    if (encoder == null) err.println(texts.get(Str.NO_ENCODER, width, height))
    encoder?.let { stream to it }
} catch (error: FfmpegMissing) {
    err.println(texts.get(Str.VIDEO_NEEDS_FFMPEG, file, error.program))
    null
} catch (error: IOException) {
    err.println(texts.get(Str.NOT_PHOTO_OR_VIDEO, file, error.message))
    null
}

/**
 * Each frame of [reader], of [stream]'s size, composed to [view] by core and written by [writer],
 * which is finished after the last, or removed if there was none; returns how many there were.
 */
private fun convertFrames(
    reader: VideoReader,
    writer: VideoWriter,
    view: View,
    stream: VideoStream,
    progress: Progress,
): Long {
    val frame = Image(stream.width, stream.height)
    val (width, height) = composedSize(view, stream.width, stream.height)
    val composed = Image(width, height)
    var done = 0L
    while (reader.read(frame.pixels)) {
        compose(frame, view, composed, { meanLinearRgb(frame) })
        writer.write(composed.pixels)
        progress.show(++done)
    }
    if (done > 0) writer.close() else writer.abort()
    return done
}

/**
 * How far a conversion of [total] frames, or about as many, is, as "Converting: 42%" on [err],
 * rewritten in place each time the share changes; only where [err] is a [terminal].
 */
private class Progress(
    private val texts: Texts,
    private val err: PrintStream,
    private val terminal: Boolean,
    private val total: Long,
) {
    private var shown = -1

    fun show(done: Long) {
        @Suppress("MagicNumber")
        val percent = (100.0 * done / maxOf(total, 1)).roundToInt().coerceAtMost(100)
        if (!terminal || percent == shown) return
        shown = percent
        err.print("\r" + texts.get(Str.CONVERTING, texts.get(Str.PERCENT, percent)))
        err.flush()
    }

    /** Ends the line the share was shown on, if it was. */
    fun end() {
        if (shown >= 0) err.println()
    }
}
