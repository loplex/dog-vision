package cz.loplex.dogvision.cli

import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.io.PrintStream
import java.util.Locale
import kotlin.system.exitProcess

/** The command line: it converts a photo, and says that the window is the desktop app's. */
fun main(args: Array<String>) {
    exitProcess(runCommandLine(args.toList(), systemTexts(), System.out, System.err))
}

/** The texts in the system's language, as the JVM takes it from the environment, or in English if it has none. */
fun systemTexts(): Texts = Texts.forLanguages(listOf(Locale.getDefault().toLanguageTag()))

/**
 * Reads [args] and does what they ask for, worded by [texts]:
 * prints the usage, or converts a photo, or says that the window is the desktop app's when given no photo.\
 * Returns the exit status: 2 for a command line that cannot be read, or that gives no photo.
 */
fun runCommandLine(args: List<String>, texts: Texts, out: PrintStream, err: PrintStream): Int {
    val arguments = try {
        parseArguments(args)
    } catch (error: UsageException) {
        err.println(texts.get(Str.USAGE_SYNOPSIS, COMMAND))
        err.println(texts.get(Str.USAGE_ERROR, COMMAND, error.message(texts)))
        return 2
    }
    return when {
        arguments.help -> 0.also { out.println(usage(texts)) }
        arguments.file == null -> 2.also { err.println(texts.get(Str.NO_WINDOW)) }
        else -> convertPhoto(arguments, texts, out, err)
    }
}
