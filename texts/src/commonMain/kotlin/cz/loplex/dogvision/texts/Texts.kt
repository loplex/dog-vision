package cz.loplex.dogvision.texts

import cz.loplex.dogvision.core.ColourVision
import cz.loplex.dogvision.core.FactTexts
import cz.loplex.dogvision.core.Note
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.percent

/**
 * The wording in one [language]: the strings of strings.xml, with English for any the language lacks, as Android falls
 * back to the default folder's.
 */
class Texts private constructor(
    val language: String,
    private val strings: Map<Str, String>,
    private val plurals: Map<Plural, Map<String, String>>,
) {
    /** The string [key], with each %1$s, %2$d and so on replaced by [args] and %% by %, as Android's getString. */
    fun get(key: Str, vararg args: Any?): String = format(strings.getValue(key), args)

    /** The plural [key] for [count], its quantity as the language's plural rules choose it, as getQuantityString. */
    fun plural(key: Plural, count: Int, vararg args: Any?): String {
        val quantities = plurals.getValue(key)
        return format(quantities[pluralCategory(language, count)] ?: quantities.getValue("other"), args)
    }

    /** The language's decimal separator. */
    val decimalSeparator: Char = decimalSeparator(language)

    fun speciesName(species: Species): String = get(species.nameKey)

    fun colourVision(kind: ColourVision): String = get(kind.nameKey)

    /** The species' name with its kind of colour vision, for lists and captions: "dog (dichromat)". */
    fun speciesLabel(species: Species): String =
        get(Str.SPECIES_LABEL, speciesName(species), colourVision(species.colourVision))

    /** What each image of [view] shows, left to right or top to bottom. */
    fun captions(view: View, differenceShare: Double?): List<String> {
        val right = speciesLabel(view.params.species)
        if (!view.sideBySide) return listOf(right)
        val left = view.compare?.let(::speciesLabel) ?: get(Str.ORIGINAL)
        if (!view.difference || differenceShare == null) return listOf(left, right)
        return listOf(left, right, get(Str.DIFFERENCE_CAPTION, percent(differenceShare, differenceShare, facts)))
    }

    /** The facts about a species, worded in this language. */
    val facts: FactTexts = object : FactTexts {
        override val decimalSeparator = this@Texts.decimalSeparator

        override fun colourVision(kind: ColourVision) = this@Texts.colourVision(kind)

        override fun coneTypes(kind: String, count: Int) = plural(Plural.CONE_TYPES, count, kind, count)

        override fun percent(value: String) = get(Str.PERCENT, value)

        override fun percentRange(low: String, high: String) = get(Str.PERCENT_RANGE, low, high)

        override fun shareOfCones(share: String) = get(Str.SHARE_OF_CONES, share)

        override val assumed = get(Str.ASSUMED)
        override val noColourAxis = get(Str.NO_COLOUR_AXIS)
        override val seesOnlyGrey = get(Str.SEES_ONLY_GREY)

        override fun rnlOfFixed(gains: List<String>) = get(Str.RNL_OF_FIXED, gains.joinToString(get(Str.GAINS_AND)))

        override val notFoundMeasured = get(Str.NOT_FOUND_MEASURED)
        override val leftSharp = get(Str.LEFT_SHARP)

        override fun acuity(value: String) = get(Str.ACUITY_VALUE, value)

        override fun acuityAcross(value: String) = get(Str.ACUITY_ACROSS, value)

        override fun acuityUp(value: String) = get(Str.ACUITY_UP, value)

        override fun note(note: Note) = get(note.key)
    }

    companion object {
        private val PLACEHOLDER = Regex("""%(?:(\d+)\$[sd]|%)""")

        /** The languages there are strings for, the first the one a string missing from another is taken from. */
        val LANGUAGES: List<String> = listOf("en") + (STRINGS.keys - "en").sorted()

        /** The strings of [language], with English for any it lacks. */
        fun of(language: String): Texts {
            val chain = listOf(LANGUAGES.first(), language).distinct()
            return Texts(
                language,
                chain.fold(emptyMap()) { all, it -> all + STRINGS[it].orEmpty() },
                chain.fold(emptyMap()) { all, it -> all + PLURALS[it].orEmpty() },
            )
        }

        /**
         * The strings of the first of [languages] there are strings for, each a language tag such as "cs-CZ", or of
         * English if there are none, as Android picks a resource folder from the system's languages.
         */
        fun forLanguages(languages: List<String>): Texts {
            val supported = languages.map { it.substringBefore('-').lowercase() }.firstOrNull { it in LANGUAGES }
            return of(supported ?: LANGUAGES.first())
        }

        /** [template] with its placeholders replaced by [args]; without args, as it stands, as getString gives it. */
        private fun format(template: String, args: Array<out Any?>): String {
            if (args.isEmpty()) return template
            return PLACEHOLDER.replace(template) { match ->
                val index = match.groupValues[1]
                if (index.isEmpty()) "%" else args[index.toInt() - 1].toString()
            }
        }
    }
}

/** Which quantity, such as "one" or "few", [count] takes in [language]. */
internal expect fun pluralCategory(language: String, count: Int): String

/** The character [language] separates the decimals of a number with. */
internal expect fun decimalSeparator(language: String): Char
