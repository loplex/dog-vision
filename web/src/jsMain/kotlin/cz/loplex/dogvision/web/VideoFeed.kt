package cz.loplex.dogvision.web

import kotlinx.browser.document
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.url.URL
import org.w3c.files.File

/**
 * A video file played over and over without its sound, as the Android app plays one, into a video element of its own
 * that the page never shows; [rotation] is what its file says to turn it by, read before it starts to play. [onReady]
 * is called once its first frame can be shown, and [onFailed] with what the browser says if it cannot be played,
 * before that or after. While it plays, [onFrame] is handed the video for each new frame, as the browser renders the
 * page.
 */
internal class VideoFeed(
    file: File,
    private val onFrame: (HTMLVideoElement) -> Unit,
    onReady: () -> Unit,
    onFailed: (message: String) -> Unit,
) {
    val name: String = file.name

    /** The degrees clockwise the file says to turn its frames by to stand upright. */
    var rotation = 0
        private set
    private val url = URL.createObjectURL(file)
    private val video = (document.createElement("video") as HTMLVideoElement).apply {
        muted = true
        loop = true
        setAttribute("playsinline", "")
    }

    /** Counts the times it was played or paused, so that a frame of an earlier playing is not handed over. */
    private var generation = 0
    private var closed = false

    init {
        video.addEventListener("loadeddata", { if (!closed) onReady() }, js("({ once: true })"))
        video.addEventListener("error", {
            if (!closed) onFailed(video.error?.asDynamic()?.message as String? ?: "")
        })
        readRotation(file) {
            rotation = it
            if (!closed) {
                video.src = url
                play()
            }
        }
    }

    /** Plays it on from where it is, or from its start. */
    fun play() {
        if (closed) return
        val generation = ++generation
        // A play() cut short by pause() or close() rejects; the video's error event says what else went wrong.
        video.play().catch { }
        awaitFrames(video, { generation == this.generation && !closed }) { onFrame(video) }
    }

    /** Pauses it where it is, as while the page is hidden. */
    fun pause() {
        generation++
        video.pause()
    }

    /** Stops it for good and lets go of the file. */
    fun close() {
        pause()
        closed = true
        video.removeAttribute("src")
        video.load()
        URL.revokeObjectURL(url)
    }
}
