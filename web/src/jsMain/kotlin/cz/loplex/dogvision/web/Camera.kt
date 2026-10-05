package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.Facing
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.mediacapture.MediaStream

/**
 * A camera's live image, through getUserMedia, into a video element of its own that the page never shows.\
 * While it runs, [onFrame] is handed the video for each new frame, as the browser renders the page;
 * [onEnded] is called if the browser ends the stream itself, as when the camera is unplugged.
 */
internal class Camera(private val onFrame: (video: HTMLVideoElement) -> Unit, private val onEnded: () -> Unit) {
    private val video = (document.createElement("video") as HTMLVideoElement).apply {
        muted = true
        setAttribute("playsinline", "")
    }
    private var stream: MediaStream? = null

    /** Counts the streams asked for, so that what one started or stopped since hands over is ignored. */
    private var generation = 0

    /** Whether a stream runs. */
    val running: Boolean get() = stream != null

    /**
     * Stops the stream running, if any, and starts the camera whose device id is [id],
     * or the back one where the device tells them apart for null.\
     * [onStarted] is handed the camera started, before its first frame,
     * then [onListed] the cameras the browser knows of, which it names only once a camera was allowed;
     * or [onFailed] is handed the name and the message of the DOMException getUserMedia rejected with.
     */
    fun start(
        id: String?,
        onStarted: (CameraOption) -> Unit,
        onListed: (List<CameraOption>) -> Unit,
        onFailed: (name: String, message: String) -> Unit,
    ) {
        stop()
        val generation = generation
        val constraints = js("({ audio: false, video: { width: { ideal: 1280 }, height: { ideal: 720 } } })")
        // Asked for as the Android app asks for its frames,
        // 1280 x 720, whichever camera it gets, at a size the camera itself has:
        // Firefox 156 on Android, held upright, crops 1280 x 720 turned to the middle 720 x 720 otherwise.
        constraints.video.resizeMode = "none"
        if (id == null) {
            constraints.video.facingMode = "environment"
        } else {
            constraints.video.deviceId = js("({})")
            constraints.video.deviceId.exact = id
        }
        window.navigator.mediaDevices.getUserMedia(constraints).then(
            onFulfilled = { stream ->
                if (generation != this.generation) {
                    stream.getTracks().forEach { it.stop() }
                    return@then
                }
                this.stream = stream
                val track = stream.getVideoTracks().first()
                val settings = track.getSettings().asDynamic()
                val started = CameraOption(
                    settings.deviceId as String? ?: id.orEmpty(),
                    track.label.ifEmpty { null },
                    facing(settings.facingMode as String?),
                )
                track.addEventListener("ended", {
                    if (generation == this.generation) {
                        stop()
                        onEnded()
                    }
                })
                onStarted(started)
                video.srcObject = stream
                // A play() cut short by the next stream rejects; that stream starts its own.
                video.play().catch { }
                awaitFrames(video, { generation == this.generation }) { onFrame(video) }
                window.navigator.mediaDevices.enumerateDevices().then({ devices ->
                    if (generation != this.generation) return@then
                    val cameras = devices.filter { it.kind.toString() == "videoinput" }.map { device ->
                        if (device.deviceId == started.id) {
                            started
                        } else {
                            val capabilities = device.asDynamic().getCapabilities?.call(device)
                            val facings = capabilities?.facingMode as Array<String>?
                            CameraOption(device.deviceId, device.label.ifEmpty { null }, facing(facings?.firstOrNull()))
                        }
                    }
                    onListed(cameras)
                }, { if (generation == this.generation) onListed(listOf(started)) })
            },
            onRejected = { error ->
                if (generation == this.generation) onFailed(error.asDynamic().name as String, error.message.orEmpty())
            },
        )
    }

    /** Stops the stream running, if any, which turns the camera's light off. */
    fun stop() {
        generation++
        stream?.getTracks()?.forEach { it.stop() }
        stream = null
        video.srcObject = null
    }

    companion object {
        /** Whether the browser offers a camera to this page at all: it offers none outside a secure context. */
        val available: Boolean get() = window.navigator.asDynamic().mediaDevices?.getUserMedia != undefined

        /**
         * Where a camera faces, as a [facingMode] of Media Capture names it:
         * "user" towards the viewer, "environment" away; a laptop's webcam often says nothing.
         */
        fun facing(facingMode: String?): Facing = when (facingMode) {
            "user" -> Facing.FRONT
            "environment" -> Facing.BACK
            else -> Facing.UNKNOWN
        }
    }
}
