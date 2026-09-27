package cz.loplex.dogvision.texts

import java.text.DecimalFormatSymbols
import java.util.Locale

// The JVM has no public plural rules, and ICU4J, which has them, is 13 MB, so each language's integer rules are here,
// as CLDR gives them; a language without them fails its test.
internal actual fun pluralCategory(language: String, count: Int): String = when (language) {
    "cs" -> when (count) {
        1 -> "one"
        in 2..4 -> "few"
        else -> "other"
    }

    "en" -> if (count == 1) "one" else "other"

    else -> error("No plural rules for $language")
}

internal actual fun decimalSeparator(language: String): Char =
    DecimalFormatSymbols.getInstance(Locale.forLanguageTag(language)).decimalSeparator
