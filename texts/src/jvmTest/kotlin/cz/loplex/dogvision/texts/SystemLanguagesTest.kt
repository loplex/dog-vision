package cz.loplex.dogvision.texts

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** The system's languages are read as gettext reads them: LANGUAGE first, as a list. */
class SystemLanguagesTest {
    private val czech = Locale.forLanguageTag("cs-CZ")

    private fun textsFor(language: String, locale: Locale = czech) =
        Texts.forLanguages(systemLanguages(mapOf("LANGUAGE" to language), locale)).language

    @Test
    fun languageListsTheLanguagesInItsOrderInPlaceOfTheLocale() {
        assertEquals(
            listOf("cs-CZ", "en", "sr"),
            systemLanguages(mapOf("LANGUAGE" to "cs_CZ.UTF-8:en::sr@latin"), Locale.ENGLISH),
        )
        assertEquals("en", textsFor("en"))
    }

    @Test
    fun withoutLanguageTheLocaleCounts() {
        assertEquals(listOf("cs-CZ"), systemLanguages(emptyMap(), czech))
        assertEquals(listOf("cs-CZ"), systemLanguages(mapOf("LANGUAGE" to ""), czech))
    }

    @Test
    fun theFirstLanguageListedThatThereAreStringsForIsTaken() {
        assertEquals("cs", textsFor("de:cs:en", Locale.ENGLISH))
    }

    @Test
    fun aListOfNoneThereAreStringsForIsEnglishWhateverTheLocale() {
        assertEquals("en", textsFor("de:fr"))
    }
}
