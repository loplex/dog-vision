package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.View
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.url.URL
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** A recording of a photo's view is a video of its images side by side, at the photo's size, that a browser plays. */
class RecordingTest {
    @Test
    fun aPhotoIsRecordedAsAVideoOfItsImages(): Promise<Unit> {
        val pixels = Uint8Array(64 * 32 * 4)
        for (i in 0 until 64 * 32) {
            pixels[i * 4] = 200.toByte()
            pixels[i * 4 + 3] = -1
        }
        val recording = Recording.start(2, 64, 32, Arrangement.ROW) { fail("the recorder failed: $it") }
        recording.upload(64, 32, pixels)
        recording.compose(View())
        return Promise { resolve, reject ->
            window.setTimeout({
                recording.stop { video ->
                    try {
                        assertTrue(video.size.toInt() > 0, "an empty video")
                        assertTrue(video.type.startsWith("video/"), "a video typed ${video.type}")
                    } catch (error: Throwable) {
                        reject(error)
                        return@stop
                    }
                    val player = document.createElement("video") as HTMLVideoElement
                    player.muted = true
                    player.onloadedmetadata = {
                        try {
                            assertEquals(128 to 32, player.videoWidth to player.videoHeight)
                            resolve(Unit)
                        } catch (error: Throwable) {
                            reject(error)
                        }
                    }
                    player.onerror = { _, _, _, _, _ -> reject(AssertionError("the browser cannot play it")) }
                    player.src = URL.createObjectURL(video)
                }
            }, 800)
        }
    }
}
