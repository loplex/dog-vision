package cz.loplex.dogvision.desktop.media

import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.ffmpeg.FfmpegMissing
import cz.loplex.dogvision.ffmpeg.VideoWriter
import cz.loplex.dogvision.ffmpeg.bestEncoder
import cz.loplex.dogvision.ffmpeg.runProgram
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.roundToLong

/** Frames a second a recording is written at, whatever rate the view is drawn at. */
const val RECORDING_FPS = 30

/** What a recording writes its frames into: an .mp4 through ffmpeg, or a test's own. */
interface RecordingSink {
    /** The format written and the encoder that writes it, such as H.265 and libx265. */
    val format: String
    val encoder: String

    /** Writes [pixels], a frame of the sink's size, 0xRRGGBB a pixel. */
    fun write(pixels: IntArray)

    /** Finishes the video. */
    fun close()

    /** Stops writing and removes what was written. */
    fun abort()
}

/** Whether ffmpeg can be run, as a recording needs it: `ffmpeg -version` is, from where the feeds run it. */
fun ffmpegRuns(): Boolean = try {
    runProgram.run(listOf("ffmpeg", "-version"), VERSION_SECONDS)
    true
} catch (_: FfmpegMissing) {
    false
}

private const val VERSION_SECONDS = 10L

/**
 * An .mp4 at [output] of [width] x [height], silent, at [RECORDING_FPS], by the best encoder ffmpeg has here;
 * an IOException where ffmpeg cannot be run or has no encoder for the size.
 */
fun ffmpegRecording(output: File, width: Int, height: Int): RecordingSink {
    val encoder = bestEncoder(width, height) ?: throw IOException("ffmpeg has no encoder for $width x $height")
    val writer = VideoWriter.silent(output, width, height, encoder, RECORDING_FPS)
    return object : RecordingSink {
        override val format = encoder.format
        override val encoder = encoder.name

        override fun write(pixels: IntArray) = writer.write(pixels)

        override fun close() = writer.close()

        override fun abort() = writer.abort()
    }
}

/**
 * A recording of the view into [output], opened through [open] at the size of the first frame:
 * each frame is written for as long as it was shown, at [RECORDING_FPS] by [clock], in nanoseconds,
 * so that the video keeps the clock's pace whatever rate the view is drawn at,
 * and a still photo is as long as it was recorded.
 *
 * Frames wait for the writer on a thread of the recorder's own, [QUEUE] at most;
 * one more is dropped, as is one of another size than the first.\
 * [stop] ends the video at the time it is called, finished on that thread;
 * [done] says once it is, and [error] why it failed, its file removed then.
 */
class Recorder(
    val output: File,
    private val open: (output: File, width: Int, height: Int) -> RecordingSink = ::ffmpegRecording,
    private val clock: () -> Long = System::nanoTime,
) {
    /** When the recording started, by the clock. */
    val startedAt = clock()

    /** The sink the frames go into, once the first came. */
    @Volatile
    var sink: RecordingSink? = null
        private set

    @Volatile
    var done = false
        private set

    @Volatile
    var error: IOException? = null
        private set

    /** Each frame with when it was shown; a frame is made on the recorder's thread, as stitching takes a moment. */
    private val frames = ArrayBlockingQueue<Pair<Long, () -> Image>>(QUEUE)

    @Volatile
    private var stoppedAt: Long? = null

    // The recorder's thread's own, set before it starts.
    private var shown: Image? = null
    private var firstAt = 0L
    private var written = 0L

    private val thread = thread(name = "dog-vision-recorder", isDaemon = true) { record() }

    /** Shows the frame [frame] makes from now on; dropped if the writer is [QUEUE] frames behind. */
    fun add(frame: () -> Image) {
        if (stoppedAt == null) frames.offer(clock() to frame)
    }

    /** Ends the video now; it is finished on the recorder's thread, as [done] says. */
    fun stop() {
        if (stoppedAt == null) stoppedAt = clock()
    }

    /** Waits up to [millis] for the video to be finished. */
    fun await(millis: Long) = thread.join(millis)

    private fun record() {
        try {
            while (true) {
                val next = frames.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
                val stop = stoppedAt
                when {
                    next != null && (stop == null || next.first <= stop) -> show(next.first, next.second())
                    next == null && stop != null -> break
                }
            }
            val last = shown ?: throw IOException("No frame was shown while recording")
            writeUntil(checkNotNull(stoppedAt))
            // Stopped within half a frame of the first.
            if (written == 0L) checkNotNull(sink).write(last.pixels)
            checkNotNull(sink).close()
        } catch (failure: IOException) {
            error = failure
            sink?.abort()
        } finally {
            done = true
        }
    }

    private fun show(at: Long, frame: Image) {
        val before = shown
        if (before == null) {
            firstAt = at
            sink = open(output, frame.width, frame.height)
        } else {
            writeUntil(at)
            if (frame.width != before.width || frame.height != before.height) return
        }
        shown = frame
    }

    /** Repeats the frame shown for every frame of the video that falls before the time [at]. */
    private fun writeUntil(at: Long) {
        val frame = shown ?: return
        val due = ((at - firstAt) * RECORDING_FPS / NANOS_A_SECOND.toDouble()).roundToLong()
        while (written < due) {
            checkNotNull(sink).write(frame.pixels)
            written++
        }
    }

    private companion object {
        const val QUEUE = 8
        const val POLL_MILLIS = 50L
        const val NANOS_A_SECOND = 1_000_000_000L
    }
}
