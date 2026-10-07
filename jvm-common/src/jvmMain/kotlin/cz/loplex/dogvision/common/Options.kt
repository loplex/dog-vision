package cz.loplex.dogvision.common

import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.io.File

/** The options of what is shown, which the command line and the windows both take. */
data class ViewOptions(val params: Params = Params(), val compare: Species? = null, val difference: Boolean = false) {
    /** The view a file is converted to: the simulation alone unless another species or the map is asked for. */
    val conversionView: View
        get() = View(params, sideBySide = compare != null || difference, compare = compare, difference = difference)

    /** These options with [option] read into them, or null if [option] is none of them. */
    fun read(option: Option): ViewOptions? = when (option.name) {
        "--species" -> copy(params = params.copy(species = species(option.name, option.value())))
        "--compare" -> copy(compare = species(option.name, option.value()))
        "--difference" -> option.flag().let { copy(difference = true) }
        "--adaptation" -> copy(params = params.copy(adaptation = share(option.name, option.value())))
        "--strength" -> copy(params = params.copy(strength = share(option.name, option.value())))
        "--chroma-scale" -> copy(params = params.copy(chromaScale = chromaScale(option.name, option.value())))
        "--acuity" -> option.flag().let { copy(params = params.copy(acuity = true)) }
        "--fov" -> copy(params = params.copy(fieldOfView = fieldOfView(option.name, option.value())))
        else -> null
    }

    companion object {
        /** Their lines of a usage, worded by [texts]. */
        fun usage(texts: Texts): List<Pair<String, String>> {
            val defaults = Params()
            return listOf(
                "--species ID" to texts.get(Str.USAGE_OPTION_SPECIES, defaults.species.id),
                "--compare ID" to texts.get(Str.USAGE_OPTION_COMPARE),
                "--difference" to texts.get(Str.USAGE_OPTION_DIFFERENCE),
                "--adaptation X" to texts.get(Str.USAGE_OPTION_ADAPTATION, defaults.adaptation),
                "--strength X" to texts.get(Str.USAGE_OPTION_STRENGTH, defaults.strength),
                "--chroma-scale S" to texts.get(Str.USAGE_OPTION_CHROMA_SCALE, defaults.chromaScale.name.lowercase()),
                "--acuity" to texts.get(Str.USAGE_OPTION_ACUITY),
                "--fov DEGREES" to texts.get(Str.USAGE_OPTION_FOV, defaults.fieldOfView),
            )
        }
    }
}

/** A command line that cannot be read, with the string [key] and the [args] that say what is wrong with it. */
class UsageException(val key: Str, vararg val args: Any?) : Exception(Texts.of("en").get(key, *args)) {
    /** What is wrong, worded by [texts]. */
    @Suppress("SpreadOperator")
    fun message(texts: Texts): String = texts.get(key, *args)
}

/** An option named [name], whose value follows it or an equals sign. */
class Option internal constructor(
    val name: String,
    private val inline: String?,
    private val remaining: ArrayDeque<String>,
) {
    /** Its value, taken off the command line. */
    fun value(): String = inline ?: remaining.removeFirstOrNull() ?: throw UsageException(Str.USAGE_NEEDS_VALUE, name)

    /** Throws unless it was given without a value, as a flag is. */
    fun flag() {
        if (inline != null) throw UsageException(Str.USAGE_TAKES_NO_VALUE, name)
    }
}

/**
 * Reads [args] as `command [options] [file]` from [start]:
 * one file, handed to [file], and options, each handed to [option], which returns null for one it does not know.\
 * Throws UsageException for anything it cannot read.
 */
fun <T> readCommandLine(args: List<String>, start: T, file: T.(File) -> T, option: T.(Option) -> T?): T {
    var read = start
    var given: String? = null
    val remaining = ArrayDeque(args)
    while (remaining.isNotEmpty()) {
        val arg = remaining.removeFirst()
        if (!arg.startsWith("-") || arg == "-") {
            if (given != null) throw UsageException(Str.USAGE_ONE_FILE, arg)
            given = arg
            read = read.file(File(arg))
            continue
        }
        val name = arg.substringBefore('=')
        val inline = if ('=' in arg) arg.substringAfter('=') else null
        read = read.option(Option(name, inline, remaining)) ?: throw UsageException(Str.USAGE_UNKNOWN_OPTION, name)
    }
    return read
}

/** A usage of [synopsis] and [about], then [examples] and [options] in two columns each, and the species. */
fun usage(
    texts: Texts,
    synopsis: String,
    about: String,
    examples: List<Pair<String, String>>,
    options: List<Pair<String, String>>,
): String {
    // Each list in two columns, the second as far in as its longest first column needs.
    fun columns(rows: List<Pair<String, String>>): String {
        val width = rows.maxOf { it.first.length } + 3
        return rows.joinToString("\n") { (left, right) -> "  ${left.padEnd(width)}$right" }
    }
    return listOf(
        synopsis,
        about,
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

@Suppress("MagicNumber")
private fun fieldOfView(option: String, value: String): Double =
    number(option, value).takeIf { it > 0 && it < 360 } ?: throw UsageException(Str.USAGE_NOT_ANGLE, option, value)
