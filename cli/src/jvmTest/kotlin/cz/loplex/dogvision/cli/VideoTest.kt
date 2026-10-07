package cz.loplex.dogvision.cli

import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.core.meanLinearRgb
import cz.loplex.dogvision.ffmpeg.VideoReader
import cz.loplex.dogvision.ffmpeg.VideoStream
import cz.loplex.dogvision.texts.Texts
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A video given on the command line is converted frame by frame, as core converts a photo, with its sound. */
class VideoTest {
    @TempDir
    lateinit var directory: File

    private fun run(
        vararg args: String,
        language: String = "en",
        terminal: Boolean = false,
    ): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status = runCommandLine(
            args.toList(),
            Texts.of(language),
            PrintStream(out, true),
            PrintStream(err, true),
            terminal,
        )
        return Triple(status, out.toString(), err.toString())
    }

    /** A second of a 64 x 48 video at 10 frames a second, red on the left, blue on the right, with [sound] or not. */
    private fun clip(sound: Boolean = true): File {
        val file = File(directory, if (sound) "clip.mp4" else "silent.mp4")
        val graph = "color=c=red:s=32x48:r=10:d=1[a];color=c=blue:s=32x48:r=10:d=1[b];[a][b]hstack[out0]"
        val audio = if (sound) listOf("-f", "lavfi", "-i", "sine=d=1", "-c:a", "aac") else emptyList()
        val command = listOf("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", graph) + audio +
            listOf("-c:v", "libx264", "-pix_fmt", "yuv444p", "-qp", "0", file.path)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return file
    }

    private fun firstFrame(file: File): Image {
        val stream = VideoStream.probe(file)
        val frame = Image(stream.width, stream.height)
        VideoReader(file, stream).use { assertTrue(it.read(frame.pixels)) }
        return frame
    }

    @Test
    fun aVideoIsConvertedFrameByFrameAsCoreComposesIt() {
        val input = clip()
        val (status, out, err) = run("--species", "cat", "$input")
        assertEquals(0, status, err)
        val output = File(directory, "clip.cat.mp4")
        val wrote = Regex("""Wrote \Q$output\E: (H\.265|H\.264|MPEG-4) \(\S+\), with the original sound""")
        assertTrue(wrote.matches(out.trim()), out)
        assertEquals("", err)
        val written = VideoStream.probe(output)
        assertEquals(VideoStream(64, 48, "10/1", 10, "aac"), written)
        val view = View(Params(Species.CAT), sideBySide = false)
        val expected = Image(64, 48)
        val original = firstFrame(input)
        compose(original, view, expected, { meanLinearRgb(original) })
        val frame = firstFrame(output)
        for (x in listOf(16, 48)) {
            val at = 24 * 64 + x
            val near = (0..16 step 8).all { shift ->
                abs((expected.pixels[at] shr shift and 0xFF) - (frame.pixels[at] shr shift and 0xFF)) <= 2
            }
            assertTrue(near, "${expected.pixels[at].toString(16)} came out as ${frame.pixels[at].toString(16)}")
        }
    }

    @Test
    fun aVideoBesideItsDifferenceIsTheSizeCoreLaysOut() {
        val (status, _, err) = run("--difference", "--output-dir", "${File(directory, "out")}", "${clip()}")
        assertEquals(0, status, err)
        val view = View(sideBySide = true, difference = true)
        val written = VideoStream.probe(File(directory, "out/clip.dog.mp4"))
        assertEquals(composedSize(view, 64, 48), written.width to written.height)
    }

    @Test
    fun aVideoWithoutSoundIsSaidToHaveNone() {
        val (status, out, _) = run("${clip(sound = false)}")
        assertEquals(0, status)
        assertTrue(out.trim().endsWith("; the original has no sound"), out)
    }

    @Test
    fun howFarAConversionIsShowsOnATerminalAlone() {
        val input = clip()
        val (_, _, shown) = run("$input", terminal = true)
        assertTrue(shown.startsWith("\rConverting: 10%"), shown)
        assertTrue(shown.endsWith("\rConverting: 100%" + System.lineSeparator()), shown)
        val (_, _, hidden) = run("$input")
        assertEquals("", hidden)
    }

    @Test
    fun theConversionSpeaksTheLanguageItIsGiven() {
        val (_, out, err) = run("${clip()}", language = "cs", terminal = true)
        assertTrue(out.trim().endsWith(", s původním zvukem"), out)
        assertTrue(out.startsWith("Zapsáno "), out)
        assertTrue("\rPřevádím: 100 %" in err, err)
    }
}
