package cz.loplex.dogvision.desktop

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Snapshots and recordings go to the user's pictures by default, where each system keeps them, else to the home. */
class PicturesTest {
    @TempDir
    lateinit var directory: File

    private val home = "/home/someone"

    /** user-dirs.dirs in [directory], as XDG_CONFIG_HOME, holding [lines]. */
    private fun userDirs(vararg lines: String): Map<String, String> {
        File(directory, "user-dirs.dirs").writeText(lines.joinToString("\n", postfix = "\n"))
        return mapOf("XDG_CONFIG_HOME" to directory.path)
    }

    @Test
    fun onLinuxItIsXdgPicturesDirAsUserDirsSetsIt() {
        val czech = userDirs(
            "# This file is written by xdg-user-dirs-update",
            $$"XDG_DESKTOP_DIR=\"$HOME/Plocha\"",
            $$"XDG_PICTURES_DIR=\"$HOME/Obrázky\"",
        )
        assertEquals(File(home, "Obrázky"), picturesFolder(czech, "Linux", home))
        val whole = userDirs("XDG_PICTURES_DIR=\"/data/Fotky \\\"nové\\\"\"")
        assertEquals(File("/data/Fotky \"nové\""), picturesFolder(whole, "Linux", home))
        val last = userDirs($$"XDG_PICTURES_DIR=\"$HOME/a\"", $$"  XDG_PICTURES_DIR = \"$HOME/b\"  ")
        assertEquals(File(home, "b"), picturesFolder(last, "Linux", home))
    }

    @Test
    fun onLinuxItIsTheHomeWhereUserDirsNamesNone() {
        assertEquals(File(home), picturesFolder(userDirs($$"XDG_PICTURES_DIR=\"$HOME/\""), "Linux", home))
        assertEquals(File(home), picturesFolder(userDirs($$"XDG_PICTURES_DIR=\"$HOME\""), "Linux", home))
        assertEquals(File(home), picturesFolder(userDirs("XDG_PICTURES_DIR=\"Pictures\""), "Linux", home))
        assertEquals(File(home), picturesFolder(userDirs($$"XDG_MUSIC_DIR=\"$HOME/Hudba\""), "Linux", home))
        val missing = mapOf("XDG_CONFIG_HOME" to File(directory, "none").path)
        assertEquals(File(home), picturesFolder(missing, "Linux", home))
    }

    @Test
    fun withoutXdgConfigHomeUserDirsIsInDotConfig() {
        File(directory, ".config").mkdir()
        File(directory, ".config/user-dirs.dirs").writeText($$"XDG_PICTURES_DIR=\"$HOME/Bilder\"\n")
        assertEquals(File(directory, "Bilder"), picturesFolder(emptyMap(), "Linux", directory.path))
    }

    @Test
    fun onWindowsItIsTheKnownFolderAndOnMacPictures() {
        val known = File("D:/OneDrive/Pictures")
        assertEquals(known, picturesFolder(emptyMap(), "Windows 11", home) { known })
        assertEquals(File(home), picturesFolder(emptyMap(), "Windows 11", home) { null })
        assertEquals(File(home, "Pictures"), picturesFolder(emptyMap(), "Mac OS X", home) { null })
    }

    @Test
    fun aFileThatCannotBeReadNamesNone() {
        assertNull(xdgPictures(directory, home))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun windowsSaysWhereThePicturesAreAsDotNetDoes() {
        val script = $$"[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false; " +
            "[Environment]::GetFolderPath('MyPictures')"
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).start()
        process.outputStream.close()
        val expected = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        process.waitFor()
        assertEquals(File(expected), windowsPictures())
    }
}
