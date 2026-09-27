package cz.loplex.dogvision.texts

import cz.loplex.dogvision.core.View
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The texts word a string or a plural as Android's getString and getQuantityString do, and every language says
 * everything, as Android lint's MissingTranslation would check.
 */
class TextsTest {
    @Test
    fun everyLanguageHasEveryStringAndPlural() {
        Texts.LANGUAGES.forEach { language ->
            assertEquals(Str.entries.toSet(), STRINGS.getValue(language).keys, "strings of $language")
            assertEquals(Plural.entries.toSet(), PLURALS.getValue(language).keys, "plurals of $language")
        }
    }

    @Test
    fun czechTakesOneFewAndOther() {
        val texts = Texts.of("cs")
        assertEquals("monochromat, 1 typ čípků", texts.plural(Plural.CONE_TYPES, 1, "monochromat", 1))
        assertEquals("dichromat, 2 typy čípků", texts.plural(Plural.CONE_TYPES, 2, "dichromat", 2))
        assertEquals("trichromat, 3 typy čípků", texts.plural(Plural.CONE_TYPES, 3, "trichromat", 3))
        assertEquals("x, 5 typů čípků", texts.plural(Plural.CONE_TYPES, 5, "x", 5))
    }

    @Test
    fun englishTakesOneAndOther() {
        val texts = Texts.of("en")
        assertEquals("monochromat, 1 cone type", texts.plural(Plural.CONE_TYPES, 1, "monochromat", 1))
        assertEquals("dichromat, 2 cone types", texts.plural(Plural.CONE_TYPES, 2, "dichromat", 2))
    }

    @Test
    fun namesEachLanguageInItself() {
        assertEquals("English", Texts.of("en").get(Str.LANGUAGE_NAME))
        assertEquals("Čeština", Texts.of("cs").get(Str.LANGUAGE_NAME))
    }

    @Test
    fun separatesDecimalsAsTheLanguageDoes() {
        assertEquals('.', Texts.of("en").decimalSeparator)
        assertEquals(',', Texts.of("cs").decimalSeparator)
    }

    @Test
    fun replacesPlaceholdersOnlyWhenGivenArguments() {
        val texts = Texts.of("en")
        assertEquals("42%", texts.get(Str.PERCENT, "42"))
        assertEquals("%1\$s%%", texts.get(Str.PERCENT))
    }

    @Test
    fun resolvesEscapesQuotesAndEntitiesAsAapt2() {
        val texts = Texts.of("en")
        assertEquals(" and ", texts.get(Str.GAINS_AND))
        val adaptation = texts.get(Str.ABOUT_ADAPTATION)
        assertTrue("each cone's signal" in adaptation, adaptation)
        assertTrue("(von Kries adaptation to a \"grey world\")" in adaptation, adaptation)
        assertTrue("daylight.\n\nAt 0" in adaptation, adaptation)
        assertTrue("Neitz, Geist & Jacobs 1989" in texts.get(Str.ABOUT_FACT_NEUTRAL_POINT))
    }

    @Test
    fun picksTheFirstLanguageThereAreStringsFor() {
        assertEquals("cs", Texts.forLanguages(listOf("sk-SK", "cs-CZ", "en-GB")).language)
        assertEquals("en", Texts.forLanguages(listOf("de-DE")).language)
        assertEquals("en", Texts.forLanguages(emptyList()).language)
    }

    @Test
    fun captionsTheOriginalAndTheDifference() {
        val captions = Texts.of("en").captions(View(sideBySide = true, difference = true), 0.25)
        assertEquals(listOf("original", "dog (dichromat)"), captions.take(2))
        assertTrue("25" in captions[2], captions[2])
    }
}
