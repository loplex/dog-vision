package cz.loplex.dogvision.desktop

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The system's ffmpeg plays a video file upright, over and over, as the window shows it, and ffmpeg's list of Windows's
 * cameras is read as it words it.
 */
class FfmpegFeedTest {
    @TempDir
    lateinit var directory: File

    /**
     * A second of a 32 x 16 video at 10 frames a second, red on the left and blue on the right, whose file says to
     * turn it [rotation] degrees counter-clockwise, as ffmpeg's -display_rotation takes it.
     */
    private fun video(rotation: Int): File {
        val plain = File(directory, "plain.mp4").path
        val file = File(directory, "video-$rotation.mp4")
        val graph = "color=c=red:s=16x16:r=10:d=1[a];color=c=blue:s=16x16:r=10:d=1[b];[a][b]hstack[out0]"
        run("-f", "lavfi", "-i", graph, "-c:v", "libx264", "-pix_fmt", "yuv444p", "-qp", "0", plain)
        run("-display_rotation", "$rotation", "-i", plain, "-c", "copy", file.path)
        return file
    }

    private fun run(vararg args: String) {
        val process = ProcessBuilder(listOf("ffmpeg", "-v", "error", "-y") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }

    @Test
    fun aVideoTurnedAQuarterIsProbedAtItsUprightSize() {
        assertEquals(32 to 16, FfmpegFeed.probe(emptyList(), video(0).path))
        assertEquals(16 to 32, FfmpegFeed.probe(emptyList(), video(90).path))
    }

    @Test
    fun whatIsNoVideoCannotBeProbed() {
        val text = File(directory, "text.mp4").apply { writeText("not a video") }
        assertFailsWith<IOException> { FfmpegFeed.probe(emptyList(), text.path) }
        assertFailsWith<IOException> { FfmpegFeed.probe(emptyList(), File(directory, "missing.mp4").path) }
    }

    /**
     * The frames come upright, turned as the file says, and keep coming after the video's end: the second of it gives
     * ten, and twenty-five come.
     */
    @Test
    fun aVideoPlaysUprightOverAndOver() {
        val frames = CountDownLatch(25)
        val ended = AtomicReference<String>()
        val ends = AtomicReference<Pair<String, String>>()
        val onFrame = { frame: Frame ->
            ends.set(colour(frame, 8, 2) to colour(frame, 8, 29))
            frames.countDown()
        }
        val feed = FfmpegFeed.video(video(90), onFrame, ended::set)
        try {
            assertTrue(frames.await(20, TimeUnit.SECONDS), "only ${25 - frames.count} frames came")
        } finally {
            feed.close()
        }
        assertEquals(16 to 32, feed.width to feed.height)
        // Turned counter-clockwise, the left half is at the bottom.
        assertEquals("blue" to "red", ends.get())
        assertNull(ended.get())
    }

    @Test
    fun aProgramThatCannotRunIsSaidToBeMissing() {
        val error = assertFailsWith<FfmpegMissing> { FfmpegFeed.start(listOf("dog-vision-no-such-ffmpeg")) }
        assertEquals("dog-vision-no-such-ffmpeg", error.program)
    }

    /**
     * cat copies its standard input until it ends, so it ends at once with nothing to copy; Windows's sort, which
     * Windows finds in its system folder before the PATH, reads it to its end as well.
     */
    @Test
    fun aProgramStartedReadsNothing() {
        val program = if (onWindows) "sort" else "cat"
        val process = FfmpegFeed.start(listOf(program))
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "$program still waits for its input")
        assertEquals("", process.inputStream.bufferedReader().readText())
    }

    /**
     * What ffmpeg 9.0.2 lists under Wine, whose DirectShow shows Video4Linux's camera, shortened, with another camera
     * after it.
     */
    @Test
    fun directShowCamerasAreListedByTheirAlternativeNames() {
        val listed = """
            [in#0 @ 00007ffffe846280] "Integrated Camera: Integrated C" (video)
            [in#0 @ 00007ffffe846280]   Alternative name "@device_cm_{860BB310-5D01-11D0-BD3B-00A0C911CE86}\video0"
            [in#0 @ 00007ffffe846280] "Microphone (Family 17h/19h HD A" (audio)
            [in#0 @ 00007ffffe846280] "OBS Virtual Camera" (video)
            Error opening input file dummy.
        """.trimIndent()
        val cameras = listOf("""@device_cm_{860BB310-5D01-11D0-BD3B-00A0C911CE86}\video0""", "OBS Virtual Camera")
        assertEquals(cameras, FfmpegFeed.directShowCameras(listed))
    }

    @Test
    fun directShowCamerasAreListedUnderTheirHeadingByOlderFfmpeg() {
        val listed = """
            [dshow @ 0000000000346f00] DirectShow video devices (some may be both video and audio devices)
            [dshow @ 0000000000346f00]  "USB2.0 HD UVC WebCam"
            [dshow @ 0000000000346f00]     Alternative name "@device_pnp_\\?\usb#vid_13d3"
            [dshow @ 0000000000346f00] DirectShow audio devices
            [dshow @ 0000000000346f00]  "Microphone (Realtek High Definition Audio)"
            [dshow @ 0000000000346f00]     Alternative name "@device_cm_{33D9A762}\wave_{A1B2}"
            dummy: Immediate exit requested
        """.trimIndent()
        assertEquals(listOf("""@device_pnp_\\?\usb#vid_13d3"""), FfmpegFeed.directShowCameras(listed))
        assertEquals(emptyList(), FfmpegFeed.directShowCameras("dummy: Immediate exit requested"))
    }

    private fun colour(frame: Frame, x: Int, y: Int): String {
        val at = (y * frame.width + x) * 4
        val red = frame.pixels.get(at).toInt() and 0xFF
        val blue = frame.pixels.get(at + 2).toInt() and 0xFF
        return when {
            red > 200 && blue < 60 -> "red"
            blue > 200 && red < 60 -> "blue"
            else -> "rgb $red ${frame.pixels.get(at + 1).toInt() and 0xFF} $blue"
        }
    }
}
