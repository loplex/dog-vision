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

/** The texts in the system's language, as [systemLanguages] lists it, or in English if there are none for it. */
fun systemTexts(): Texts = Texts.forLanguages(systemLanguages())

/**
 * The languages the system asks for, most wanted first, as gettext reads them:
 * the list in [environment]'s LANGUAGE, colon-separated, where it is set, or else [locale],
 * which the JVM takes from LC_ALL, LC_MESSAGES or LANG, or from Windows' language.\
 * Each is a language tag such as "cs-CZ".
 */
fun systemLanguages(environment: Map<String, String> = System.getenv(), locale: Locale = Locale.getDefault()) =
    environment["LANGUAGE"].orEmpty().split(':').filter { it.isNotEmpty() }
        .map { it.substringBefore('.').substringBefore('@').replace('_', '-') }
        .ifEmpty { listOf(locale.toLanguageTag()) }

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
