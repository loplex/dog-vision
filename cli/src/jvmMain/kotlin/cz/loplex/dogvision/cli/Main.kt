package cz.loplex.dogvision.cli

import cz.loplex.dogvision.common.UsageException
import cz.loplex.dogvision.common.info
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import cz.loplex.dogvision.texts.systemTexts
import java.io.Console
import java.io.PrintStream
import kotlin.system.exitProcess

/** The command line: it converts a photo or a video, and says that the window is the desktop app's. */
fun main(args: Array<String>) {
    exitProcess(runCommandLine(args.toList(), systemTexts(), System.out, System.err, onTerminal()))
}

/**
 * Whether this program runs on a terminal, as far as the JVM tells:
 * it has a console where its standard input and output are a terminal,
 * which from Java 22 on it says by Console.isTerminal(), and before by having one at all.\
 * The JVM cannot tell whether the standard error alone is redirected.
 */
fun onTerminal(): Boolean {
    val console = System.console() ?: return false
    return runCatching { Console::class.java.getMethod("isTerminal").invoke(console) as Boolean }.getOrDefault(true)
}

/**
 * Reads [args] and does what they ask for, worded by [texts]:
 * prints the usage, or the model derived for the species,
 * or converts a photo or a video, showing how far a video is where [err] is a [terminal],
 * or says that the window is the desktop app's when given no file.\
 * Returns the exit status: 2 for a command line that cannot be read, or that gives no file.
 */
fun runCommandLine(
    args: List<String>,
    texts: Texts,
    out: PrintStream,
    err: PrintStream,
    terminal: Boolean = false,
): Int {
    val arguments = try {
        parseArguments(args)
    } catch (error: UsageException) {
        err.println(texts.get(Str.USAGE_SYNOPSIS, COMMAND))
        err.println(texts.get(Str.USAGE_ERROR, COMMAND, error.message(texts)))
        return 2
    }
    return when {
        arguments.help -> 0.also { out.println(usage(texts)) }
        arguments.info -> 0.also { out.println(info(arguments.view.params, texts)) }
        arguments.file == null -> 2.also { err.println(texts.get(Str.NO_WINDOW)) }
        else -> convertFile(arguments, texts, out, err, terminal)
    }
}
