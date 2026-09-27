package cz.loplex.dogvision.cli

import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.io.File

/** The command line's options, as the Python program's `dog-vision` takes them. */
data class Arguments(
    /** The photo or video to convert, or to show if [window]. */
    val file: File? = null,
    /** Show [file] in the window instead of converting it. */
    val window: Boolean = false,
    /** The camera the window shows when no file is given, counted from 0: /dev/video followed by it on Linux. */
    val camera: Int = 0,
    val params: Params = Params(),
    val compare: Species? = null,
    val difference: Boolean = false,
    /** Where a converted file goes, in place of next to its original. */
    val outputDir: File? = null,
    /** How the window draws on Windows; null for ANGLE, and WGL where ANGLE cannot start. */
    val windowsGl: WindowsGl? = null,
    val help: Boolean = false,
) {
    /** Whether this asks for [file] to be converted, rather than for the window. */
    val converts: Boolean get() = file != null && !window && !help

    /** The view a file is converted to: the simulation alone unless another species or the map is asked for. */
    val conversionView: View
        get() = View(params, sideBySide = compare != null || difference, compare = compare, difference = difference)

    /** The view the window starts with: side by side, as the Python window starts. */
    val windowView: View get() = View(params, sideBySide = true, compare = compare, difference = difference)
}

/** The two ways the window can draw on Windows, as --gl names them. */
enum class WindowsGl(val id: String) {
    /** OpenGL ES over Direct3D 11. */
    ANGLE("angle"),

    /** The graphics driver's own desktop OpenGL. */
    WGL("wgl"),
}

/** A command line that cannot be read, with the string [key] and the [args] that say what is wrong with it. */
class UsageException(val key: Str, vararg val args: Any?) : Exception(Texts.of("en").get(key, *args)) {
    /** What is wrong, worded by [texts]. */
    fun message(texts: Texts): String = texts.get(key, *args)
}

/**
 * Reads [args] as `dog-vision [options] [file]` does: an option's value follows it or an equals sign. Throws
 * UsageException for anything it does not know.
 */
fun parseArguments(args: List<String>): Arguments {
    var arguments = Arguments()
    var params = Params()
    val remaining = ArrayDeque(args)
    while (remaining.isNotEmpty()) {
        val arg = remaining.removeFirst()
        if (!arg.startsWith("-") || arg == "-") {
            if (arguments.file != null) throw UsageException(Str.USAGE_ONE_FILE, arg)
            arguments = arguments.copy(file = File(arg))
            continue
        }
        val name = arg.substringBefore('=')
        val inline = if ('=' in arg) arg.substringAfter('=') else null

        fun value(): String =
            inline ?: remaining.removeFirstOrNull() ?: throw UsageException(Str.USAGE_NEEDS_VALUE, name)

        fun flag() {
            if (inline != null) throw UsageException(Str.USAGE_TAKES_NO_VALUE, name)
        }
        when (name) {
            "-h", "--help" -> flag().also { arguments = arguments.copy(help = true) }
            "--window" -> flag().also { arguments = arguments.copy(window = true) }
            "--difference" -> flag().also { arguments = arguments.copy(difference = true) }
            "--acuity" -> flag().also { params = params.copy(acuity = true) }
            "--camera" -> arguments = arguments.copy(camera = camera(name, value()))
            "--output-dir" -> arguments = arguments.copy(outputDir = File(value()))
            "--gl" -> arguments = arguments.copy(windowsGl = windowsGl(name, value()))
            "--species" -> params = params.copy(species = species(name, value()))
            "--compare" -> arguments = arguments.copy(compare = species(name, value()))
            "--adaptation" -> params = params.copy(adaptation = share(name, value()))
            "--strength" -> params = params.copy(strength = share(name, value()))
            "--chroma-scale" -> params = params.copy(chromaScale = chromaScale(name, value()))
            "--fov" -> params = params.copy(fieldOfView = fieldOfView(name, value()))
            else -> throw UsageException(Str.USAGE_UNKNOWN_OPTION, name)
        }
    }
    return arguments.copy(params = params)
}

/** How to call it, for --help, worded by [texts]; its first line is repeated after a mistake. */
fun usage(texts: Texts): String {
    val defaults = Params()
    val examples = listOf(
        "dog-vision" to texts.get(Str.USAGE_CAMERA),
        "dog-vision photo.jpg" to texts.get(Str.USAGE_PHOTO),
        "dog-vision --window clip.mp4" to texts.get(Str.USAGE_WINDOW),
    )
    val options = listOf(
        "--species ID" to texts.get(Str.USAGE_OPTION_SPECIES, defaults.species.id),
        "--compare ID" to texts.get(Str.USAGE_OPTION_COMPARE),
        "--difference" to texts.get(Str.USAGE_OPTION_DIFFERENCE),
        "--adaptation X" to texts.get(Str.USAGE_OPTION_ADAPTATION, defaults.adaptation),
        "--strength X" to texts.get(Str.USAGE_OPTION_STRENGTH, defaults.strength),
        "--chroma-scale S" to texts.get(Str.USAGE_OPTION_CHROMA_SCALE, defaults.chromaScale.name.lowercase()),
        "--acuity" to texts.get(Str.USAGE_OPTION_ACUITY),
        "--fov DEGREES" to texts.get(Str.USAGE_OPTION_FOV, defaults.fieldOfView),
        "--window" to texts.get(Str.USAGE_OPTION_WINDOW),
        "--camera N" to texts.get(Str.USAGE_OPTION_CAMERA, Arguments().camera),
        "--output-dir DIR" to texts.get(Str.USAGE_OPTION_OUTPUT_DIR),
        "--gl API" to texts.get(Str.USAGE_OPTION_GL),
        "-h, --help" to texts.get(Str.USAGE_OPTION_HELP),
    )

    // Each list in two columns, the second as far in as its longest first column needs, as argparse aligns them.
    fun columns(rows: List<Pair<String, String>>): String {
        val width = rows.maxOf { it.first.length } + 3
        return rows.joinToString("\n") { (left, right) -> "  ${left.padEnd(width)}$right" }
    }
    return listOf(
        texts.get(Str.USAGE_SYNOPSIS),
        texts.get(Str.USAGE_ABOUT),
        columns(examples),
        texts.get(Str.USAGE_OPTIONS) + "\n" + columns(options),
        texts.get(Str.USAGE_SPECIES) + " " + Species.entries.joinToString(" ") { it.id },
    ).joinToString("\n\n")
}

private fun species(option: String, id: String): Species =
    Species.entries.firstOrNull { it.id == id } ?: throw UsageException(Str.USAGE_NO_SPECIES, option, id)

private fun chromaScale(option: String, value: String): ChromaScale =
    ChromaScale.entries.firstOrNull { it.name.lowercase() == value }
        ?: throw UsageException(Str.USAGE_NOT_CHROMA_SCALE, option, value)

private fun number(option: String, value: String): Double =
    value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw UsageException(Str.USAGE_NOT_NUMBER, option, value)

private fun share(option: String, value: String): Double =
    number(option, value).takeIf { it in 0.0..1.0 } ?: throw UsageException(Str.USAGE_NOT_SHARE, option, value)

private fun fieldOfView(option: String, value: String): Double =
    number(option, value).takeIf { it > 0 && it < 360 } ?: throw UsageException(Str.USAGE_NOT_ANGLE, option, value)

private fun windowsGl(option: String, value: String): WindowsGl =
    WindowsGl.entries.firstOrNull { it.id == value } ?: throw UsageException(Str.USAGE_NOT_GL, option, value)

private fun camera(option: String, value: String): Int =
    value.toIntOrNull()?.takeIf { it >= 0 } ?: throw UsageException(Str.USAGE_NOT_CAMERA, option, value)
