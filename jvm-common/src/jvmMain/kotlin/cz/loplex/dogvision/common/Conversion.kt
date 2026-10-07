package cz.loplex.dogvision.common

import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.core.meanLinearRgb
import cz.loplex.dogvision.core.shownName
import cz.loplex.dogvision.ffmpeg.Encoder
import cz.loplex.dogvision.ffmpeg.FfmpegMissing
import cz.loplex.dogvision.ffmpeg.Sound
import cz.loplex.dogvision.ffmpeg.VideoReader
import cz.loplex.dogvision.ffmpeg.VideoStream
import cz.loplex.dogvision.ffmpeg.VideoWriter
import cz.loplex.dogvision.ffmpeg.bestEncoder
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Where [file] converted to [view] goes,
 * named after it and the species [view] shows, with the [extension] of what it is written as:
 * photo.jpg as photo.cat.png, or photo.horse-vs-cat.png where it shows a horse beside a cat,
 * next to it or in [outputDir].
 */
fun convertedFile(file: File, view: View, outputDir: File?, extension: String = "png"): File {
    val name = file.name.substringBeforeLast('.').ifEmpty { file.name } + ".${shownName(view)}.$extension"
    return File(outputDir ?: file.parentFile, name)
}

/**
 * Why a file could not be converted, with the string [key] and the [args] that say it,
 * as a [UsageException] does, and the [cause] it came of.
 */
class ConversionException(val key: Str, vararg val args: Any?, cause: Throwable? = null) :
    Exception(Texts.of("en").get(key, *args), cause) {
    /** Why, worded by [texts]. */
    @Suppress("SpreadOperator")
    fun message(texts: Texts): String = texts.get(key, *args)
}

/** What a conversion wrote, into [file]. */
sealed interface Converted {
    val file: File

    /** A photo, with the share of its pixels the map of differences marks, where it has the map. */
    data class Photo(override val file: File, val differenceShare: Double?) : Converted

    /** A video, written by [encoder], with the original's sound or without it, as [sound] says. */
    data class Video(override val file: File, val encoder: Encoder, val sound: Sound) : Converted
}

/**
 * The photo or the video [file] converted at full size to [view],
 * into [convertedFile]'s file in [outputDir] or next to it:
 * a photo by core, a video frame by frame, each as a photo is, the scene's mean taken again for each,
 * into an .mp4 of the best encoder ffmpeg has here, with the original's sound.
 *
 * [run] converts on the thread that calls it; [cancel], from any thread, stops it and removes the unfinished file.
 */
class Conversion(private val file: File, private val view: View, private val outputDir: File?) {
    @Volatile
    private var cancelled = false

    @Volatile
    private var video: Pair<VideoReader, VideoWriter>? = null

    /** Stops the conversion, which then writes nothing, and removes what it has written of a video. */
    fun cancel() {
        cancelled = true
        video?.let { (reader, writer) -> stop(reader, writer) }
    }

    /**
     * Converts, telling [onPercent] how far a video is each time the share changes, and returns what it wrote,
     * or null if it was cancelled.\
     * Throws [ConversionException] where it cannot convert.
     */
    fun run(onPercent: (Int) -> Unit = {}): Converted? {
        val photo = try {
            readPhoto(file)
        } catch (error: IOException) {
            throw ConversionException(Str.PHOTO_UNREADABLE, file, error.message, cause = error)
        }
        return if (photo == null) runVideo(onPercent) else runPhoto(photo)
    }

    private fun runPhoto(photo: Image): Converted? {
        val output = convertedFile(file, view, outputDir)
        val (width, height) = composedSize(view, photo.width, photo.height)
        // core writes into the image's own pixels, which ImageIO then encodes without a copy.
        val composed = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val pixels = (composed.raster.dataBuffer as DataBufferInt).data
        val share = compose(photo, view, Image(width, height, pixels), { meanLinearRgb(photo) })
        if (cancelled) return null
        try {
            output.parentFile?.mkdirs()
            if (!ImageIO.write(composed, "png", output)) throw IOException("no PNG writer")
        } catch (error: IOException) {
            throw ConversionException(Str.PHOTO_UNWRITABLE, output, error.message, cause = error)
        }
        return Converted.Photo(output, share)
    }

    @Suppress("ThrowsCount")
    private fun runVideo(onPercent: (Int) -> Unit): Converted? {
        val (stream, encoder) = prepare()
        val (width, height) = composedSize(view, stream.width, stream.height)
        val output = convertedFile(file, view, outputDir, "mp4")
        output.parentFile?.mkdirs()
        val reader = VideoReader(file, stream)
        val writer = try {
            VideoWriter(output, file, stream, width, height, encoder)
        } catch (error: IOException) {
            reader.close()
            throw ConversionException(Str.PHOTO_UNWRITABLE, output, error.message, cause = error)
        }
        video = reader to writer
        // A cancel that came before the reader and the writer were there to stop.
        if (cancelled) stop(reader, writer)
        val frames = try {
            convertFrames(reader, writer, stream, onPercent)
        } catch (error: IOException) {
            writer.abort()
            // A cancel that stopped ffmpeg in the middle of a read or a write; one between frames ends the loop.
            if (cancelled) return null
            throw ConversionException(Str.CONVERSION_FAILED, error.message, cause = error)
        } finally {
            reader.close()
        }
        return when (frames) {
            null -> null
            0L -> throw ConversionException(Str.VIDEO_NO_FRAMES, file)
            else -> Converted.Video(output, encoder, writer.sound)
        }
    }

    /**
     * What ffprobe says of the video [file], and the encoder that writes it converted to [view];
     * a [ConversionException]
     * where the file is no video, ffmpeg cannot be run, or none of its encoders writes a video of that size.
     */
    private fun prepare(): Pair<VideoStream, Encoder> = try {
        val stream = VideoStream.probe(file)
        val (width, height) = composedSize(view, stream.width, stream.height)
        stream to (bestEncoder(width, height) ?: throw ConversionException(Str.NO_ENCODER, width, height))
    } catch (error: FfmpegMissing) {
        throw ConversionException(Str.VIDEO_NEEDS_FFMPEG, file, error.program, cause = error)
    } catch (error: IOException) {
        throw ConversionException(Str.NOT_PHOTO_OR_VIDEO, file, error.message, cause = error)
    }

    /**
     * Each frame of [reader], of [stream]'s size, composed to [view] by core and written by [writer],
     * which is finished after the last, or removed if there was none or the conversion was cancelled;
     * returns how many there were, or null if it was cancelled.
     */
    private fun convertFrames(
        reader: VideoReader,
        writer: VideoWriter,
        stream: VideoStream,
        onPercent: (Int) -> Unit,
    ): Long? {
        val frame = Image(stream.width, stream.height)
        val (width, height) = composedSize(view, stream.width, stream.height)
        val composed = Image(width, height)
        var done = 0L
        var shown = -1
        while (!cancelled && reader.read(frame.pixels)) {
            compose(frame, view, composed, { meanLinearRgb(frame) })
            writer.write(composed.pixels)
            done++
            @Suppress("MagicNumber")
            val percent = (100.0 * done / maxOf(stream.frames, 1)).roundToInt().coerceAtMost(100)
            if (percent != shown) {
                shown = percent
                onPercent(percent)
            }
        }
        if (cancelled || done == 0L) writer.abort() else writer.close()
        return done.takeUnless { cancelled }
    }

    private fun stop(reader: VideoReader, writer: VideoWriter) {
        reader.close()
        writer.abort()
    }
}
