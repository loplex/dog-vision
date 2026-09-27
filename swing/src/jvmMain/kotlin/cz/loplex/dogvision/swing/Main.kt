package cz.loplex.dogvision.swing

import cz.loplex.dogvision.cli.runCommandLine
import cz.loplex.dogvision.cli.systemTexts
import kotlin.system.exitProcess

/**
 * `dog-vision-swing`, as `dog-vision`: a photo given alone is converted by the command line, in the system's language,
 * and anything else opens the Swing window.
 */
fun main(args: Array<String>) {
    exitProcess(runCommandLine(args.toList(), systemTexts(), System.out, System.err, ::showWindow))
}
