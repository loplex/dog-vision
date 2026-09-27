package cz.loplex.dogvision.cli

import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.io.PrintStream
import java.util.Locale
import kotlin.system.exitProcess

/** The command line alone, without the window: it converts a photo, and says to use the desktop app for the rest. */
fun main(args: Array<String>) {
    val texts = systemTexts()
    exitProcess(
        runCommandLine(args.toList(), texts, System.out, System.err) {
            System.err.println(texts.get(Str.NO_WINDOW))
            2
        },
    )
}

/** The texts in the system's language, as the JVM takes it from the environment, or in English if it has none. */
fun systemTexts(): Texts = Texts.forLanguages(listOf(Locale.getDefault().toLanguageTag()))

/**
 * Reads [args] and does what they ask for from the command line, worded by [texts]: prints the usage, or converts a
 * photo; anything else, the window, is [window]'s to do. Returns the exit status: 2 for a command line that cannot be
 * read, as Python's argparse has it.
 */
fun runCommandLine(
    args: List<String>,
    texts: Texts,
    out: PrintStream,
    err: PrintStream,
    window: (Arguments) -> Int,
): Int {
    val arguments = try {
        parseArguments(args)
    } catch (error: UsageException) {
        err.println(texts.get(Str.USAGE_SYNOPSIS))
        err.println(texts.get(Str.USAGE_ERROR, error.message(texts)))
        return 2
    }
    return when {
        arguments.help -> 0.also { out.println(usage(texts)) }
        arguments.converts -> convertPhoto(arguments, texts, out, err)
        else -> window(arguments)
    }
}
