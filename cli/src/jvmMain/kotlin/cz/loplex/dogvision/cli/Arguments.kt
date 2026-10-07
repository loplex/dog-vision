package cz.loplex.dogvision.cli

import cz.loplex.dogvision.common.ViewOptions
import cz.loplex.dogvision.common.readCommandLine
import cz.loplex.dogvision.common.usage
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.io.File

/** The command line's name, which its usage and its mistakes say. */
const val COMMAND = "dog-vision-cli"

/** The command line's options. */
data class Arguments(
    /** The photo or the video to convert. */
    val file: File? = null,
    val view: ViewOptions = ViewOptions(),
    /** Where a converted file goes, in place of next to its original. */
    val outputDir: File? = null,
    /** Print the model derived for the species, and its checks, in place of converting. */
    val info: Boolean = false,
    val help: Boolean = false,
)

/** Reads [args] as the command line takes them. */
fun parseArguments(args: List<String>): Arguments = readCommandLine(args, Arguments(), { copy(file = it) }) { option ->
    when (option.name) {
        "-h", "--help" -> option.flag().let { copy(help = true) }
        "--output-dir" -> copy(outputDir = File(option.value()))
        "--info" -> option.flag().let { copy(info = true) }
        else -> view.read(option)?.let { copy(view = it) }
    }
}

/** How to call the command line, for --help, worded by [texts]; its first line is repeated after a mistake. */
fun usage(texts: Texts): String = usage(
    texts,
    texts.get(Str.USAGE_SYNOPSIS, COMMAND),
    texts.get(Str.USAGE_ABOUT),
    listOf("$COMMAND photo.jpg" to texts.get(Str.USAGE_PHOTO), "$COMMAND clip.mp4" to texts.get(Str.USAGE_VIDEO)),
    ViewOptions.usage(texts) + listOf(
        "--output-dir DIR" to texts.get(Str.USAGE_OPTION_OUTPUT_DIR),
        "--info" to texts.get(Str.USAGE_OPTION_INFO),
        "-h, --help" to texts.get(Str.USAGE_OPTION_HELP),
    ),
)
