package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.cli.runCommandLine
import cz.loplex.dogvision.cli.systemTexts
import kotlin.system.exitProcess

/**
 * `dog-vision`, as the Python program's: a photo given alone is converted by the command line, in the system's
 * language, and anything else opens the window.
 */
fun main(args: Array<String>) {
    exitProcess(runCommandLine(args.toList(), systemTexts(), System.out, System.err, ::showWindow))
}

/** Whether this runs on Windows, where the window draws through ANGLE or WGL and a camera comes through DirectShow. */
internal val onWindows = System.getProperty("os.name").startsWith("Windows")
