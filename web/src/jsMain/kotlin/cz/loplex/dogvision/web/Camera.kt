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
 *
 * [onListed] is handed the cameras the browser knows of, as [listedCameras] has them:
 * at once where the page was allowed a camera before, after each start,
 * and whenever a camera is plugged in or out, but never an empty list.
 */
internal class Camera(
    private val onFrame: (video: HTMLVideoElement) -> Unit,
    private val onEnded: () -> Unit,
    private val onListed: (List<CameraOption>) -> Unit,
) {
    private val video = (document.createElement("video") as HTMLVideoElement).apply {
        muted = true
        setAttribute("playsinline", "")
    }
    private var stream: MediaStream? = null

    /** Counts the streams asked for, so that what one started or stopped since hands over is ignored. */
    private var generation = 0

    /** The camera running, as it was handed to onStarted; null while none runs. */
    private var started: CameraOption? = null

    init {
        window.navigator.mediaDevices.addEventListener("devicechange", { list() })
        list()
    }

    /** Whether a stream runs. */
    val running: Boolean get() = stream != null

    /**
     * Stops the stream running, if any, and starts the camera whose device id is [id],
     * or the back one where the device tells them apart for null.\
     * [onStarted] is handed the camera started, before its first frame, and the cameras are listed again;
     * or [onFailed] is handed the name and the message of the DOMException getUserMedia rejected with.
     */
    fun start(id: String?, onStarted: (CameraOption) -> Unit, onFailed: (name: String, message: String) -> Unit) {
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
                this.started = started
                onStarted(started)
                video.srcObject = stream
                // A play() cut short by the next stream rejects; that stream starts its own.
                video.play().catch { }
                awaitFrames(video, { generation == this.generation }) { onFrame(video) }
                list()
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
        started = null
        video.srcObject = null
    }

    /**
     * Hands onListed the cameras the browser knows of,
     * if it names any, or the one running alone where it cannot tell them.
     */
    private fun list() {
        window.navigator.mediaDevices.enumerateDevices().then({ devices ->
            val cameras = listedCameras(devices.map { it.asDynamic() }, started)
            if (cameras.isNotEmpty()) onListed(cameras)
        }, { started?.let { onListed(listOf(it)) } })
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

        /**
         * The cameras among [devices], MediaDeviceInfo objects as enumerateDevices gives them,
         * [running] as it started.\
         * None before the page was allowed a camera:
         * a browser then gives a device no id to ask for it by, and no name.\
         * Where it gives none but a camera runs, as it should not, that one alone.
         */
        fun listedCameras(devices: List<dynamic>, running: CameraOption?): List<CameraOption> {
            val named = devices.filter { it.kind == "videoinput" && (it.deviceId as String).isNotEmpty() }
            val cameras = named.map { device ->
                val id = device.deviceId as String
                if (id == running?.id) {
                    running
                } else {
                    val capabilities = device.getCapabilities?.call(device)
                    val facings = capabilities?.facingMode as Array<String>?
                    CameraOption(id, (device.label as String).ifEmpty { null }, facing(facings?.firstOrNull()))
                }
            }
            return cameras.ifEmpty { listOfNotNull(running) }
        }
    }
}
