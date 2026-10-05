package cz.loplex.dogvision.swing

import cz.loplex.dogvision.cli.systemTexts
import cz.loplex.dogvision.desktop.runWindow
import cz.loplex.dogvision.desktop.sayFromWindow
import kotlin.system.exitProcess

/** The Swing window's name, which its usage and its mistakes say. */
private const val COMMAND = "dog-vision-swing"

/** `dog-vision-swing`, as `dog-vision-compose`: the Swing window, or its usage. */
fun main(args: Array<String>) {
    val say = { text: String, mistake: Boolean -> sayFromWindow(COMMAND, text, mistake) }
    exitProcess(runWindow(COMMAND, args.toList(), systemTexts(), say, ::showWindow))
}
