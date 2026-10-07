package cz.loplex.dogvision.ffmpeg

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ffmpeg is looked for on a Windows PATH as Windows words it, winget's words are read past its progress bars,
 * and a program is started with nothing on its input, or said to be missing.
 */
class FfmpegProgramsTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun aPathsFoldersAreReadPastQuotesAndEmptyEntries() {
        val path = """C:\Windows\system32;;"C:\Program Files\ffmpeg\bin"; C:\Users\me\Links ;"""
        val folders = listOf("""C:\Windows\system32""", """C:\Program Files\ffmpeg\bin""", """C:\Users\me\Links""")
        @Suppress("KotlinMisorderedAssertEqualsArguments")
        assertEquals(folders.map(::File), FfmpegPrograms.folders(path))
        @Suppress("KotlinMisorderedAssertEqualsArguments")
        assertEquals(emptyList(), FfmpegPrograms.folders(""))
    }

    /** Each program is taken from the first folder that has it, and a folder named like it does not count. */
    @Test
    fun eachProgramIsFoundInTheFirstFolderThatHasIt() {
        val empty = File(directory, "empty").apply { mkdir() }
        val first = File(directory, "first").apply { mkdir() }
        val second = File(directory, "second").apply { mkdir() }
        File(first, "ffprobe.exe").mkdir()
        File(second, "ffprobe.exe").writeText("")
        File(first, "ffmpeg.exe").writeText("")
        File(second, "ffmpeg.exe").writeText("")
        val found = FfmpegPrograms.find(listOf("ffmpeg", "ffprobe"), listOf(empty, first, second))
        val expected = mapOf("ffmpeg" to File(first, "ffmpeg.exe").path, "ffprobe" to File(second, "ffprobe.exe").path)
        assertEquals(expected, found)
        assertEquals(emptyMap(), FfmpegPrograms.find(listOf("ffmpeg"), listOf(empty)))
    }

    /** Words as winget writes them, between a spinner and a progress bar that it redraws over themselves. */
    @Test
    fun wingetsLastWordsAreTakenPastItsProgress() {
        val output = "   - \r   \\ \r\r\nFound FFmpeg [Gyan.FFmpeg] Version 9.0.2\r\n" +
            "Downloading https://github.com/GyanD/codexffmpeg/releases/download/9.0.2/ffmpeg-9.0.2-full_build.zip\r\n" +
            "  ██████████████▒▒▒▒▒▒  60.0 MB /  101 MB\r  ██████████████████████   101 MB /  101 MB\r\n" +
            "An unexpected error occurred while executing the command:\r\n0x80190194 : Not found (404).\r\n  \r\n"
        assertEquals("0x80190194 : Not found (404).", FfmpegPrograms.lastSaid(output))
        assertNull(FfmpegPrograms.lastSaid("  - \r \\ \r | \r\n  ██▒▒  \r\n"))
    }

    @Test
    fun aProgramNotFoundIsRunByItsName() {
        assertEquals("dog-vision-no-such-ffmpeg", FfmpegPrograms.command("dog-vision-no-such-ffmpeg"))
    }

    @Test
    fun aProgramThatCannotRunIsSaidToBeMissing() {
        val error = assertFailsWith<FfmpegMissing> { FfmpegPrograms.start(listOf("dog-vision-no-such-ffmpeg")) }
        assertEquals("dog-vision-no-such-ffmpeg", error.program)
    }

    /**
     * cat copies its standard input until it ends, so it ends at once with nothing to copy;
     * Windows's sort, which Windows finds in its system folder before the PATH, reads it to its end as well.
     */
    @Test
    fun aProgramStartedReadsNothing() {
        val program = if (onWindows) "sort" else "cat"
        val process = FfmpegPrograms.start(listOf(program))
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "$program still waits for its input")
        assertEquals("", process.inputStream.bufferedReader().readText())
    }
}
