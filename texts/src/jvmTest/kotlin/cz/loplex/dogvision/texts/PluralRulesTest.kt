package cz.loplex.dogvision.texts

import kotlin.test.Test

/** The JVM's plural rules are written by hand, so every language there are strings for needs its own. */
class PluralRulesTest {
    @Test
    fun everyLanguageHasPluralRules() {
        Texts.LANGUAGES.forEach { pluralCategory(it, 1) }
    }
}
