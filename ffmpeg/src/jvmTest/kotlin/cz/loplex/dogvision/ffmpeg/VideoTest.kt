package cz.loplex.dogvision.ffmpeg

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A video's frames are read upright through the system's ffmpeg, and written by the best encoder it has,
 * in colours a player shows as they were, with the original's sound.
 */
class VideoTest {
    @TempDir
    lateinit var directory: File

    private fun run(vararg args: String) {
        val process = ProcessBuilder(listOf("ffmpeg", "-v", "error", "-y") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }

    /** A second of a 32 x 16 video at 10 frames a second, red on the left, blue on the right, with [sound] or not. */
    private fun video(name: String, sound: Boolean = false, rotation: Int = 0): File {
        val plain = File(directory, "plain-$name.mp4")
        val graph = "color=c=red:s=16x16:r=10:d=1[a];color=c=blue:s=16x16:r=10:d=1[b];[a][b]hstack[out0]"
        val audio = if (sound) listOf("-f", "lavfi", "-i", "sine=d=1", "-c:a", "aac") else emptyList()
        run(
            "-f", "lavfi", "-i", graph, *audio.toTypedArray(),
            "-c:v", "libx264", "-pix_fmt", "yuv444p", "-qp", "0", plain.path,
        )
        if (rotation == 0) return plain
        val turned = File(directory, "$name.mp4")
        run("-display_rotation", "$rotation", "-i", plain.path, "-c", "copy", turned.path)
        return turned
    }

    /** A writer of 64 x 64 frames from a silent video. */
    private fun silentWriter(output: File, encoder: Encoder): VideoWriter {
        val source = video("silent")
        return VideoWriter(output, source, VideoStream.probe(source), 64, 64, encoder)
    }

    private fun frames(file: File): List<IntArray> {
        val stream = VideoStream.probe(file)
        return VideoReader(file, stream).use { reader ->
            generateSequence { IntArray(stream.width * stream.height).takeIf(reader::read) }.toList()
        }
    }

    private fun near(expected: Int, actual: Int, tolerance: Int = 2) =
        (0..16 step 8).all { shift -> abs((expected shr shift and 0xFF) - (actual shr shift and 0xFF)) <= tolerance }

    @Test
    fun aVideoIsProbedForItsSizeRateFramesAndSound() {
        assertEquals(VideoStream(32, 16, "10/1", 10, "aac"), VideoStream.probe(video("sound", sound = true)))
        assertEquals("", VideoStream.probe(video("silent")).audio)
    }

    @Test
    fun aVideoTurnedAQuarterIsReadUpright() {
        val file = video("turned", rotation = 90)
        assertEquals(16 to 32, VideoStream.probe(file).let { it.width to it.height })
        val frames = frames(file)
        assertEquals(10, frames.size)
        // Turned counter-clockwise, the left half is at the bottom.
        assertTrue(near(0xFF0000FF.toInt(), frames[0][0]), frames[0][0].toString(16))
        assertTrue(near(0xFFFF0000.toInt(), frames[0].last()), frames[0].last().toString(16))
    }

    @Test
    fun whatIsNoVideoCannotBeProbed() {
        val text = File(directory, "text.mp4").apply { writeText("not a video") }
        assertFailsWith<IOException> { VideoStream.probe(text) }
    }

    @Test
    fun theBestEncoderIsTheFirstListedThatEncodesThreeFramesOfTheSize() {
        val asked = mutableListOf<List<String>>()
        val runs = Runs { command, _ ->
            asked += command
            when {
                "-encoders" in command -> Ran(0, " V....D libx265 \n V....D hevc_nvenc \n V....D libx264 \n")

                // Listed, but there is no NVIDIA card.
                "hevc_nvenc" in command -> Ran(1, "")

                // Listed, and it hangs.
                "libx265" in command -> null

                else -> Ran(0, "")
            }
        }
        assertEquals("libx264", bestEncoder(1280, 720, runs)?.name)
        val tried = asked.drop(1).map { command -> command[command.indexOf("-c:v") + 1] }
        assertEquals(listOf("libx265", "hevc_nvenc", "libx264"), tried)
        assertTrue(asked.drop(1).all { "color=c=gray:s=1280x720:d=0.1" in it }, asked.toString())
        assertTrue(asked.drop(1).all { it[it.indexOf("-frames:v") + 1] == "3" }, asked.toString())
        assertNull(bestEncoder(16, 16) { _, _ -> Ran(0, "") })
    }

    @Test
    fun libx265IsNotTriedOnAVideoUnder50PixelsWide() {
        val asked = mutableListOf<List<String>>()
        val runs = Runs { command, _ ->
            asked += command
            Ran(0, if ("-encoders" in command) " V....D libx265 \n V....D libx264 \n" else "")
        }
        assertEquals("libx264", bestEncoder(48, 1080, runs)?.name)
        assertTrue(asked.none { "libx265" in it }, asked.toString())
        assertEquals("libx265", bestEncoder(50, 32, runs)?.name)
    }

    @Test
    fun thisMachinesFfmpegHasAnEncoder() {
        assertNotNull(bestEncoder(64, 48))
    }

    /** libx265 crashes on 48 x 32, 3.5 on the second frame and 4.3 at random, and is not chosen for it. */
    @Test
    fun theEncoderChosenForASmallVideoWritesIt() {
        val output = File(directory, "small.mp4")
        val writer = VideoWriter.silent(output, 48, 32, checkNotNull(bestEncoder(48, 32)), 30)
        repeat(30) { writer.write(IntArray(48 * 32)) }
        writer.close()
        assertEquals(30L, VideoStream.probe(output).frames)
    }

    /**
     * Each frame one colour, so that 4:2:0's halved colour does not blur an edge:
     * within 2 of 255, an odd size padded to an even one.
     * libx265 3.5 crashes on some videos much smaller.
     */
    @Test
    fun framesWrittenComeBackInTheirColoursWithTheSound() {
        val colours = listOf(0x204080, 0xC08020, 0x30A050, 0xFFFFFF, 0x000000).map { it or 0xFF000000.toInt() }
        val output = File(directory, "written.mp4")
        val encoder = checkNotNull(bestEncoder(65, 49))
        val source = video("source", sound = true)
        val writer = VideoWriter(output, source, VideoStream.probe(source), 65, 49, encoder)
        for (colour in colours) writer.write(IntArray(65 * 49) { colour })
        writer.close()
        assertEquals(Sound.KEPT, writer.sound)
        val stream = VideoStream.probe(output)
        assertEquals(66 to 50, stream.width to stream.height)
        assertEquals("aac", stream.audio)
        val frames = frames(output)
        assertEquals(colours.size, frames.size)
        for ((colour, frame) in colours.zip(frames)) {
            val middle = frame[25 * 66 + 33]
            assertTrue(near(colour, middle), "${colour.toString(16)} came back as ${middle.toString(16)}")
        }
    }

    @Test
    fun aVideoWithoutSoundIsWrittenWithout() {
        val output = File(directory, "silent.mp4")
        val writer = silentWriter(output, checkNotNull(bestEncoder(64, 64)))
        writer.write(IntArray(64 * 64))
        writer.close()
        assertEquals(Sound.NONE, writer.sound)
        assertEquals("", VideoStream.probe(output).audio)
    }

    @Test
    fun aRecordingIsWrittenAtItsOwnRateWithoutSound() {
        val output = File(directory, "recorded.mp4")
        val writer = VideoWriter.silent(output, 64, 48, checkNotNull(bestEncoder(64, 48)), 30)
        repeat(15) { writer.write(IntArray(64 * 48)) }
        writer.close()
        assertEquals(Sound.NONE, writer.sound)
        assertEquals(VideoStream(64, 48, "30/1", 15, ""), VideoStream.probe(output))
    }

    @Test
    fun anEncoderThatFailsSaysWhy() {
        val output = File(directory, "failed.mp4")
        val broken = Encoder("libx264", "H.264", listOf("-crf", "not-a-number"))
        val writer = silentWriter(output, broken)
        val error = assertFailsWith<IOException> {
            repeat(1000) { writer.write(IntArray(64 * 64)) }
            writer.close()
        }
        assertTrue(error.message!!.startsWith("ffmpeg (libx264) failed: "), error.message)
        writer.abort()
        assertFalse(output.exists())
    }

    @Test
    fun anAbortedVideoLeavesNoFile() {
        val output = File(directory, "aborted.mp4")
        val writer = silentWriter(output, checkNotNull(bestEncoder(64, 64)))
        repeat(5) { writer.write(IntArray(64 * 64)) }
        writer.abort()
        assertFalse(output.exists())
    }
}
