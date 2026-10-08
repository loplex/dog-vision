package cz.loplex.dogvision.web

import cz.loplex.dogvision.texts.Menu
import cz.loplex.dogvision.texts.MenuEntry
import cz.loplex.dogvision.texts.Texts
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The page's menu drops the menus down as one list, a menu's entries in its place, under a row back up. */
class MenuListTest {
    private val container = (document.createElement("div") as HTMLElement).also { document.body!!.appendChild(it) }
    private val button = (document.createElement("button") as HTMLButtonElement).also(container::appendChild)
    private val list = (document.createElement("div") as HTMLElement).also(container::appendChild)
    private val outside = (document.createElement("p") as HTMLElement).also(container::appendChild)
    private val done = mutableListOf<String>()

    private val menus = listOf(
        Menu(
            "File",
            listOf(
                MenuEntry.Action("Open") { done += "open" },
                MenuEntry.Action("Cancel", enabled = false) { done += "cancel" },
            ),
        ),
        Menu(
            "View",
            listOf(
                MenuEntry.Check("Side by side", true) { done += "side by side $it" },
                MenuEntry.Separator,
                MenuEntry.Submenu("Compared with", listOf(MenuEntry.Choice("cat", false) { done += "cat" })),
            ),
        ),
    )

    private val menu = MenuList(button, list).also { it.show(menus, Texts.of("en")) }

    @AfterTest
    fun removeContainer() = container.remove()

    private fun rows() = list.querySelectorAll(".menu-item").asList().map { it as HTMLButtonElement }

    private fun row(text: String) = rows().single { it.textContent.orEmpty().contains(text) }

    @Test
    fun theButtonDropsTheMenusNamesDown() {
        assertTrue(list.hidden)
        button.click()
        assertFalse(list.hidden)
        assertEquals("true", button.getAttribute("aria-expanded"))
        assertEquals(listOf("File›", "View›"), rows().map { it.textContent })
    }

    @Test
    fun aMenuChosenShowsItsEntriesUnderARowBack() {
        button.click()
        row("View").click()
        assertEquals(listOf("‹ View", "✓ Side by side", "Compared with›"), rows().map { it.textContent })
        assertEquals("true", row("Side by side").getAttribute("aria-checked"))
        assertEquals(1, list.querySelectorAll("hr").length - 1)
        row("Compared with").click()
        row("cat").click()
        assertEquals(listOf("cat"), done)
        assertTrue(list.hidden)
        button.click()
        row("File").click()
        assertTrue(row("Cancel").disabled)
        row("‹ File").click()
        assertEquals(listOf("File›", "View›"), rows().map { it.textContent })
    }

    @Test
    fun anEntryChosenDoesWhatItSaysAndAClickElsewhereOrEscapeCloses() {
        button.click()
        row("View").click()
        row("Side by side").click()
        assertEquals(listOf("side by side false"), done)
        assertTrue(list.hidden)
        button.click()
        outside.click()
        assertTrue(list.hidden)
        button.click()
        document.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape")))
        assertTrue(list.hidden)
    }

    @Test
    fun anOpenListFollowsWhatTheMenusShow() {
        button.click()
        row("View").click()
        val unchecked = listOf(menus[0], Menu("View", listOf(MenuEntry.Check("Side by side", false) {})))
        menu.show(unchecked, Texts.of("en"))
        assertEquals("false", row("Side by side").getAttribute("aria-checked"))
    }
}
