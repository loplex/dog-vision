package cz.loplex.dogvision.cli

import cz.loplex.dogvision.common.Conversion
import cz.loplex.dogvision.common.ConversionException
import cz.loplex.dogvision.common.Converted
import cz.loplex.dogvision.ffmpeg.Sound
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.io.PrintStream
import kotlin.math.roundToInt

/**
 * Converts the photo or the video [Arguments.file] at full size to the view the arguments ask for,
 * as [Conversion] does, and reports on [out] what it wrote, or on [err] why it could not, worded by [texts];
 * a video's conversion shows on [err] how far it is where [err] is a [terminal].\
 * Interrupted, as by Ctrl+C, it removes the unfinished file.\
 * Returns the exit status.
 */
fun convertFile(arguments: Arguments, texts: Texts, out: PrintStream, err: PrintStream, terminal: Boolean): Int {
    val file = checkNotNull(arguments.file) { "No file to convert" }
    val conversion = Conversion(file, arguments.view.conversionView, arguments.outputDir)
    val interrupted = Thread(conversion::cancel)
    Runtime.getRuntime().addShutdownHook(interrupted)
    val progress = Progress(texts, err, terminal)
    val converted = try {
        conversion.run(progress::show)
    } catch (error: ConversionException) {
        progress.end()
        err.println(error.message(texts))
        return 1
    } finally {
        try {
            Runtime.getRuntime().removeShutdownHook(interrupted)
        } catch (_: IllegalStateException) {
            // The JVM is shutting down, which ran the hook: the conversion stopped for it, and the hook is done.
        }
    }
    progress.end()
    // Null where it was cancelled, which only the shutdown of the JVM does here.
    converted?.let { written(it, texts).forEach(out::println) }
    return if (converted == null) 1 else 0
}

/** What [converted] says it wrote, worded by [texts]: a photo's share of pixels its map marks, and the file. */
@Suppress("MagicNumber")
private fun written(converted: Converted, texts: Texts): List<String> = when (converted) {
    is Converted.Photo -> listOfNotNull(
        converted.differenceShare?.let {
            texts.get(Str.PIXELS_DIFFER, texts.get(Str.PERCENT, (it * 100).roundToInt()))
        },
        texts.get(Str.PHOTO_WRITTEN, converted.file),
    )

    is Converted.Video -> listOf(videoWritten(converted, texts))
}

private fun videoWritten(video: Converted.Video, texts: Texts): String {
    val sound = if (video.sound == Sound.KEPT) Str.WRITTEN_WITH_SOUND else Str.WRITTEN_WITHOUT_SOUND
    return texts.get(Str.VIDEO_WRITTEN, video.file, texts.get(sound, video.encoder.format, video.encoder.name))
}

/**
 * How far a conversion is, as "Converting: 42%" on [err],
 * rewritten in place each time the share changes; only where [err] is a [terminal].
 */
private class Progress(private val texts: Texts, private val err: PrintStream, private val terminal: Boolean) {
    private var shown = false

    fun show(percent: Int) {
        if (!terminal) return
        shown = true
        err.print("\r" + texts.get(Str.CONVERTING, texts.get(Str.PERCENT, percent)))
        err.flush()
    }

    /** Ends the line the share was shown on, if it was. */
    fun end() {
        if (shown) err.println()
    }
}
