package cz.loplex.dogvision.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What the windows keep from one run to the next, in [settingsFile]:
 * [outputDir], where snapshots and recordings go, null for [picturesFolder].
 */
data class Settings(val outputDir: File? = null)

/**
 * settings.json in the folder each system keeps a program's settings in: dog-vision in %APPDATA% on Windows,
 * in ~/Library/Application Support on macOS, and in $XDG_CONFIG_HOME, else ~/.config, elsewhere.
 */
fun settingsFile(
    environment: Map<String, String> = System.getenv(),
    osName: String = System.getProperty("os.name"),
    home: String = System.getProperty("user.home"),
): File {
    val base = when {
        osName.startsWith("Windows") -> environment["APPDATA"]?.let(::File) ?: File(home, "AppData/Roaming")
        osName.startsWith("Mac") -> File(home, "Library/Application Support")
        else -> configHome(environment, home)
    }
    return File(File(base, "dog-vision"), "settings.json")
}

/** Where a program keeps its settings outside Windows and macOS: $XDG_CONFIG_HOME, else ~/.config. */
internal fun configHome(environment: Map<String, String>, home: String): File =
    environment["XDG_CONFIG_HOME"]?.takeIf { it.isNotEmpty() }?.let(::File) ?: File(home, ".config")

/**
 * The settings [file] holds, and the defaults for what it does not:
 * a file that is missing, cannot be read or is no JSON object gives them all,
 * and an output folder that is no string, or an empty one, is none.
 */
fun readSettings(file: File): Settings {
    val json = try {
        Json.parseToJsonElement(file.readText()) as? JsonObject
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: return Settings()
    val outputDir = (json[OUTPUT_DIR] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    return Settings(outputDir?.let(::File))
}

/**
 * Writes [settings] into [file], keeping what else it holds, through a file beside it renamed over it,
 * so that a failure leaves the old one whole; the folder is made if it is missing.\
 * Throws IOException where it cannot be written.
 */
fun writeSettings(file: File, settings: Settings) {
    val kept = try {
        Json.parseToJsonElement(file.readText()) as? JsonObject
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
    val written: Map<String, JsonElement> =
        kept.orEmpty() + (OUTPUT_DIR to (settings.outputDir?.path?.let(::JsonPrimitive) ?: JsonNull))
    val folder = file.absoluteFile.parentFile
    if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot make $folder")
    val temporary = File.createTempFile("settings-", ".json", folder)
    try {
        temporary.writeText(PRETTY.encodeToString(JsonObject.serializer(), JsonObject(written)) + "\n")
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}

private const val OUTPUT_DIR = "output_dir"

private val PRETTY = Json { prettyPrint = true }
