package cz.loplex.dogvision.texts

import java.util.Locale

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
