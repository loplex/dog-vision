package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.ffmpeg.VideoStream
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A recording writes each frame for as long as it was shown, at 30 frames a second by its clock,
 * drops what the writer is too far behind for or what is of another size, and says why it failed, its file removed.
 */
class RecorderTest {
    @TempDir
    lateinit var directory: File

    private val now = AtomicLong()

    /** Each frame written, as the one pixel value its image has, and what was done to the sink. */
    private val written: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun sink(failAfter: Int = Int.MAX_VALUE, opened: CountDownLatch? = null) =
        { _: File, width: Int, height: Int ->
            opened?.await(WAIT_SECONDS, TimeUnit.SECONDS)
            log += "open ${width}x$height"
            object : RecordingSink {
                override val format = "H.264"
                override val encoder = "libx264"

                override fun write(pixels: IntArray) {
                    if (written.size >= failAfter) throw IOException("disk full")
                    written += pixels[0]
                }

                override fun close() {
                    log += "close"
                }

                override fun abort() {
                    log += "abort"
                }
            }
        }

    private fun recorder(open: (File, Int, Int) -> RecordingSink = sink()) =
        Recorder(File(directory, "a.mp4"), open, now::get)

    private fun frame(value: Int, width: Int = 4, height: Int = 2) = Image(
        width,
        height,
        IntArray(width * height) {
            value
        },
    )

    private fun Recorder.finish() {
        stop()
        await(WAIT_SECONDS * 1000)
        assertTrue(done)
    }

    /** Lets the recorder's thread take what was added, before the clock moves on. */
    private fun settle() = Thread.sleep(SETTLE_MILLIS)

    @Test
    fun eachFrameIsWrittenForAsLongAsItWasShown() {
        val recorder = recorder()
        recorder.add { frame(1) }
        settle()
        now.set(SECOND / 2)
        recorder.add { frame(2) }
        settle()
        now.set(SECOND)
        recorder.finish()
        assertNull(recorder.error)
        assertEquals(List(15) { 1 } + List(15) { 2 }, written.toList())
        assertEquals(listOf("open 4x2", "close"), log.toList())
    }

    @Test
    fun aFrameOfAnotherSizeIsDropped() {
        val recorder = recorder()
        recorder.add { frame(1) }
        settle()
        now.set(SECOND / 10)
        recorder.add { frame(2, width = 6) }
        settle()
        now.set(SECOND / 5)
        recorder.finish()
        assertEquals(List(6) { 1 }, written.toList())
    }

    @Test
    fun framesTheWriterIsTooFarBehindForAreDropped() {
        val opened = CountDownLatch(1)
        val recorder = recorder(sink(opened = opened))
        recorder.add { frame(0) }
        settle()
        for (value in 1..20) recorder.add { frame(value) }
        opened.countDown()
        settle()
        now.set(SECOND)
        recorder.finish()
        // Shown all at once, each replaces the one before;
        // the last the queue kept, 8 behind the first, fills the second.
        assertEquals(List(30) { 8 }, written.toList())
    }

    @Test
    fun aRecordingStoppedAtOnceHasOneFrame() {
        val recorder = recorder()
        recorder.add { frame(7) }
        settle()
        recorder.finish()
        assertEquals(listOf(7), written.toList())
    }

    @Test
    fun aRecordingWithoutAFrameFails() {
        val recorder = recorder()
        recorder.finish()
        assertEquals("No frame was shown while recording", recorder.error?.message)
        assertEquals(emptyList(), log.toList())
    }

    @Test
    fun aWriterThatFailsAbortsTheVideo() {
        val recorder = recorder(sink(failAfter = 3))
        recorder.add { frame(1) }
        settle()
        now.set(SECOND)
        recorder.finish()
        assertEquals("disk full", recorder.error?.message)
        assertEquals(listOf("open 4x2", "abort"), log.toList())
    }

    @Test
    fun aSecondRecordedThroughFfmpegIsThirtyFrames() {
        val output = File(directory, "recorded.mp4")
        val recorder = Recorder(output, clock = now::get)
        recorder.add { frame(0x204080, width = 64, height = 48) }
        settle()
        now.set(SECOND)
        recorder.finish()
        assertNull(recorder.error)
        assertEquals(VideoStream(64, 48, "30/1", 30, ""), VideoStream.probe(output))
        assertFalse(recorder.sink?.format.isNullOrEmpty())
    }

    private companion object {
        const val SECOND = 1_000_000_000L
        const val WAIT_SECONDS = 30L
        const val SETTLE_MILLIS = 200L
    }
}
