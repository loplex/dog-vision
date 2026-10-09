package cz.loplex.dogvision.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** settings.json is found where each system keeps settings, read whatever it holds, and written whole or not at all. */
class SettingsTest {
    @TempDir
    lateinit var directory: File

    private val file get() = File(directory, "settings.json")

    @Test
    fun theFileIsWhereEachSystemKeepsSettings() {
        val home = "/home/someone"
        assertEquals(
            File("/xdg/dog-vision/settings.json"),
            settingsFile(mapOf("XDG_CONFIG_HOME" to "/xdg"), "Linux", home),
        )
        assertEquals(File("$home/.config/dog-vision/settings.json"), settingsFile(emptyMap(), "Linux", home))
        assertEquals(
            File("$home/.config/dog-vision/settings.json"),
            settingsFile(mapOf("XDG_CONFIG_HOME" to ""), "Linux", home),
        )
        assertEquals(
            File("C:/Users/someone/AppData/Roaming/dog-vision/settings.json"),
            settingsFile(mapOf("APPDATA" to "C:/Users/someone/AppData/Roaming"), "Windows 11", home),
        )
        assertEquals(
            File("$home/Library/Application Support/dog-vision/settings.json"),
            settingsFile(emptyMap(), "Mac OS X", home),
        )
    }

    @Test
    fun whatCannotBeReadGivesTheDefaults() {
        assertEquals(Settings(), readSettings(file))
        for (text in listOf("", "{", "[1, 2]", "\"a\"", "null", """{"output_dir": 3}""", """{"output_dir": ""}""")) {
            file.writeText(text)
            assertEquals(Settings(), readSettings(file), text)
        }
        file.writeText("""{"output_dir": "/shots", "convert_to_output_dir": true}""")
        assertEquals(Settings(File("/shots"), convertToOutputDir = true), readSettings(file))
        for (flag in listOf("\"true\"", "1", "null", "false")) {
            file.writeText("""{"convert_to_output_dir": $flag}""")
            assertEquals(Settings(), readSettings(file), flag)
        }
        for (flag in listOf("\"false\"", "0", "null", "true")) {
            file.writeText("""{"menu_bar_shown": $flag}""")
            assertEquals(Settings(), readSettings(file), flag)
        }
        file.writeText("""{"menu_bar_shown": false}""")
        assertEquals(Settings(menuBarShown = false), readSettings(file))
    }

    @Test
    fun aWriteKeepsWhatElseTheFileHoldsAndLeavesNothingBeside() {
        file.writeText("""{"theme": "dark", "output_dir": "/old"}""")
        val written = Settings(File("/shots"), convertToOutputDir = true, menuBarShown = false)
        writeSettings(file, written)
        val json = Json.parseToJsonElement(file.readText()) as JsonObject
        assertEquals(JsonPrimitive("dark"), json["theme"])
        assertEquals(JsonPrimitive(File("/shots").path), json["output_dir"])
        assertEquals(JsonPrimitive(false), json["menu_bar_shown"])
        assertEquals(written, readSettings(file))
        assertEquals(listOf(file.name), directory.list()?.toList())
    }

    @Test
    fun theFolderIsMadeAndAFileInItsWayIsAnError() {
        val nested = File(directory, "config/dog-vision/settings.json")
        writeSettings(nested, Settings())
        assertEquals(Settings(), readSettings(nested))
        File(directory, "blocked").writeText("a file")
        assertFailsWith<IOException> { writeSettings(File(directory, "blocked/settings.json"), Settings()) }
    }
}
