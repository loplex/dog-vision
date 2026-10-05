package cz.loplex.dogvision.texts

internal actual fun pluralCategory(language: String, count: Int): String = Intl.PluralRules(language).select(count)

@Suppress("MagicNumber")
internal actual fun decimalSeparator(language: String): Char =
    Intl.NumberFormat(language).formatToParts(1.5).first { it.type == "decimal" }.value.single()

/** The JavaScript internationalisation API, as much of it as the texts use. */
private external object Intl {
    /** The plural rules of a language: which quantity, such as "one" or "few", a number takes. */
    class PluralRules(locales: String) {
        @Suppress("UnusedParameter", "RedundantSuppression") // detekt reports it, IDEA does not
        fun select(number: Int): String
    }

    class NumberFormat(locales: String) {
        @Suppress("UnusedParameter", "RedundantSuppression") // detekt reports it, IDEA does not
        fun formatToParts(number: Double): Array<Part>
    }

    /** A piece of a formatted number, such as its integer digits or its decimal separator. */
    interface Part {
        val type: String
        val value: String
    }
}
