package cz.loplex.dogvision.web

import kotlinx.browser.window
import org.w3c.dom.HTMLVideoElement

/** Calls [onFrame] for each new frame of [video], as the browser renders the page, for as long as [wanted] says so. */
internal fun awaitFrames(video: HTMLVideoElement, wanted: () -> Boolean, onFrame: () -> Unit) =
    awaitFrames(video, wanted, -1, onFrame)

/**
 * [shown] is how many frames the video had presented when one was handed over last, where the browser cannot say when
 * a frame comes.
 */
private fun awaitFrames(video: HTMLVideoElement, wanted: () -> Boolean, shown: Int, onFrame: () -> Unit) {
    if (video.asDynamic().requestVideoFrameCallback != undefined) {
        video.asDynamic().requestVideoFrameCallback { _: Double, _: dynamic ->
            if (wanted()) {
                onFrame()
                awaitFrames(video, wanted, -1, onFrame)
            }
        }
        return
    }
    // Checked once per frame of the page, which may come twice as often as the video's. A live video's time runs on
    // with the clock, not frame by frame; its count of frames does, where the browser keeps one: Firefox 156 keeps it
    // at 0, and each frame of the page is then handed over.
    window.requestAnimationFrame {
        if (wanted()) {
            val frames = video.asDynamic().getVideoPlaybackQuality?.call(video)?.totalVideoFrames as Int? ?: 0
            if (frames == 0 || frames != shown) onFrame()
            awaitFrames(video, wanted, frames, onFrame)
        }
    }
}
