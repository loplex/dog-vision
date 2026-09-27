package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.cli.readPhoto
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/** Whether this runs on Windows, where the window draws through ANGLE or WGL and a camera comes through DirectShow. */
val onWindows = System.getProperty("os.name").startsWith("Windows")

/** What the window shows: a file, which is a photo or else a video played over and over, or a camera. */
sealed interface Source {
    class Media(val file: File) : Source

    class Camera(val index: Int) : Source
}

/**
 * Why nothing is shown, worded when it is shown, in the language chosen then; [ffmpegMissing] if it is that ffmpeg
 * cannot be run, which the window offers to install on Windows.
 */
class Failure(val ffmpegMissing: Boolean = false, val words: (Texts) -> String)

/**
 * Starts showing [source] through [renderer], on a thread of its own, since a large photo takes a moment to read and
 * ffmpeg to open a camera; [onFailure] is told why it cannot be shown. A file is a photo if [photoReader] reads it, and
 * else a video. The feed it returns stops what it started, and a photo read after it is closed is not shown.
 */
fun startFeed(
    source: Source,
    renderer: Renderer,
    onFailure: (Failure) -> Unit,
    photoReader: (File) -> Image? = ::readPhoto,
): AutoCloseable {
    var feed: FfmpegFeed? = null
    var closed = false
    val lock = Object()
    thread(name = "dog-vision-source", isDaemon = true) {
        val started = try {
            when (source) {
                is Source.Media -> {
                    val photo = photoReader(source.file)
                    if (photo == null) {
                        FfmpegFeed.video(
                            source.file,
                            { renderer.show(it, live = true) },
                            { reason -> onFailure(Failure { it.get(Str.VIDEO_FAILED, source.file.name, reason) }) },
                        )
                    } else {
                        val frame = frameOf(preview(photo))
                        synchronized(lock) { if (!closed) renderer.show(frame, live = false) }
                        null
                    }
                }

                is Source.Camera -> FfmpegFeed.camera(
                    source.index,
                    { renderer.show(it, live = true) },
                    { reason -> onFailure(Failure { it.get(Str.CAMERA_FAILED, reason) }) },
                )
            }
        } catch (error: FfmpegMissing) {
            onFailure(Failure(ffmpegMissing = true) { it.get(Str.FFMPEG_MISSING, error.program) })
            null
        } catch (error: IOException) {
            val reason = error.message.orEmpty()
            onFailure(
                Failure { texts ->
                    when (source) {
                        is Source.Camera -> texts.get(Str.CAMERA_FAILED, reason)
                        is Source.Media -> texts.get(Str.MEDIA_FAILED, source.file.name) + ": $reason"
                    }
                },
            )
            null
        }
        synchronized(lock) {
            if (closed) started?.close() else feed = started
        }
    }
    // A feed that starts after this is closed closes itself.
    return AutoCloseable {
        synchronized(lock) {
            closed = true
            feed?.close()
        }
    }
}
