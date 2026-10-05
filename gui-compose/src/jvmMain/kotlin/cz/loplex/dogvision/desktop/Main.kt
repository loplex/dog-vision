package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.cli.systemTexts
import kotlin.system.exitProcess

/** The Compose window's name, which its usage and its mistakes say. */
private const val COMMAND = "dog-vision-compose"

/**
 * `dog-vision-compose`: the window on what the command line asks for,
 * or the window's usage for --help or a command line it cannot read, in the system's language.
 */
fun main(args: Array<String>) {
    val say = { text: String, mistake: Boolean -> sayFromWindow(COMMAND, text, mistake) }
    exitProcess(runWindow(COMMAND, args.toList(), systemTexts(), say, ::showWindow))
}
