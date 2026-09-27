package cz.loplex.dogvision.web

import cz.loplex.dogvision.texts.Texts
import kotlinx.browser.window
import org.w3c.dom.asList

/** The language the page speaks: the one the viewer chose, remembered by the browser, else the browser's. */
object Language {
    /** Where the browser remembers the language chosen. */
    private const val KEY = "language"

    /**
     * The language the viewer chose, remembered by this browser, or null to follow the browser's languages. Where
     * storage is blocked, a choice lasts as long as the page is open.
     */
    var chosen: String?
        get() = try {
            window.localStorage.getItem(KEY)?.takeIf { it in Texts.LANGUAGES }
        } catch (_: dynamic) {
            null
        }
        set(value) {
            try {
                if (value == null) {
                    window.localStorage.removeItem(KEY)
                } else {
                    window.localStorage.setItem(KEY, value)
                }
            } catch (_: dynamic) {
                // Storage blocked, as in some private windows: the choice is not remembered.
            }
        }

    /** The language chosen, else the browser's. */
    fun preferred(): String = chosen ?: browser()

    /** The first of the browser's languages there are strings for, or English. */
    fun browser(): String = Texts.forLanguages(window.navigator.languages.asList()).language
}
