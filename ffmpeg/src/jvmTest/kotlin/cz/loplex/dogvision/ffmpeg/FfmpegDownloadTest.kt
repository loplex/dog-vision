package cz.loplex.dogvision.ffmpeg

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The zip's line of checksums.sha256 is found, and the zip's bin folder alone unpacked, whole or not at all. */
class FfmpegDownloadTest {
    private val home = createTempDirectory("ffmpeg-download-test")

    @AfterTest
    fun deleteHome() {
        home.toFile().deleteRecursively()
    }

    @Test
    fun theZipsChecksumIsFoundAmongTheOthers() {
        val sums = """
            0123abcd  ffmpeg-master-latest-win64-gpl.zip
            ABCDEF01 *${FfmpegDownload.ZIP}
        """.trimIndent()
        assertEquals("abcdef01", FfmpegDownload.sha256Of(sums, FfmpegDownload.ZIP))
        assertNull(FfmpegDownload.sha256Of(sums, "ffmpeg-n0.1-latest-win64-gpl-shared-0.1.zip"))
    }

    @Test
    fun theBinFolderAloneIsUnpackedInPlaceOfTheOldOne() {
        val zip = zip(
            "ffmpeg-n9.0/bin/ffmpeg.exe",
            "ffmpeg-n9.0/bin/ffprobe.exe",
            "ffmpeg-n9.0/bin/avcodec-62.dll",
            "ffmpeg-n9.0/bin/ffplay.exe",
            "ffmpeg-n9.0/bin/deeper/other.exe",
            "ffmpeg-n9.0/doc/ffmpeg.html",
        )
        val destination = home.resolve("ffmpeg-9.0")
        Files.createDirectories(destination.resolve("bin"))
        destination.resolve("bin/old.dll").writeText("old")
        FfmpegDownload.unpack(zip, destination)
        assertEquals(
            listOf("avcodec-62.dll", "ffmpeg.exe", "ffprobe.exe"),
            destination.resolve("bin").listDirectoryEntries().map { it.name }.sorted(),
        )
        assertEquals("ffmpeg-n9.0/bin/ffmpeg.exe", destination.resolve("bin/ffmpeg.exe").readText())
        assertEquals(listOf("ffmpeg-9.0", "ffmpeg.zip"), home.listDirectoryEntries().map { it.name }.sorted())
    }

    @Test
    fun aZipWithoutFfprobeLeavesTheOldFolderAsItWas() {
        val zip = zip("ffmpeg-n9.0/bin/ffmpeg.exe")
        val destination = home.resolve("ffmpeg-9.0")
        Files.createDirectories(destination.resolve("bin"))
        destination.resolve("bin/ffprobe.exe").writeText("old")
        assertFailsWith<IOException> { FfmpegDownload.unpack(zip, destination) }
        assertEquals(listOf("ffprobe.exe"), destination.resolve("bin").listDirectoryEntries().map { it.name })
        assertEquals(listOf("ffmpeg-9.0", "ffmpeg.zip"), home.listDirectoryEntries().map { it.name }.sorted())
    }

    /** A zip in the test's folder with a file for each of [names], whose contents are its name. */
    private fun zip(vararg names: String): Path {
        val zip = home.resolve("ffmpeg.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { output ->
            for (name in names) {
                output.putNextEntry(ZipEntry(name))
                output.write(name.toByteArray())
                output.closeEntry()
            }
        }
        return zip
    }
}
