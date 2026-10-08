package cz.loplex.dogvision.swing

import cz.loplex.dogvision.texts.Menu
import cz.loplex.dogvision.texts.MenuEntry
import cz.loplex.dogvision.texts.MenuKey
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The menu bar lays out the menus' entries, each doing what it says, and is built again only when they change. */
class MenusTest {
    private val done = mutableListOf<String>()

    private fun menus(checked: Boolean = true) = listOf(
        Menu(
            "File",
            listOf(
                MenuEntry.Action("Open", MenuKey.OPEN) { done += "open" },
                MenuEntry.Separator,
                MenuEntry.Action("Quit", MenuKey.QUIT, enabled = false) { done += "quit" },
            ),
        ),
        Menu(
            "View",
            listOf(
                MenuEntry.Check("Side by side", checked, MenuKey.SIDE_BY_SIDE) { done += "side by side $it" },
                MenuEntry.Submenu(
                    "Compared with",
                    listOf(
                        MenuEntry.Choice("original", true) { done += "original" },
                        MenuEntry.Choice("cat", false) { done += "cat" },
                    ),
                ),
            ),
        ),
    )

    @Test
    fun theEntriesAreLaidOutWithTheirKeys() {
        val bar = menuBar(menus())
        assertEquals(listOf("File", "View"), (0 until bar.menuCount).map { bar.getMenu(it).text })
        val file = bar.getMenu(0)
        assertEquals(3, file.itemCount)
        assertEquals(KeyStroke.getKeyStroke("ctrl O"), file.getItem(0).accelerator)
        assertNull(file.getItem(1))
        assertFalse(file.getItem(2).isEnabled)
        val side = assertIs<JCheckBoxMenuItem>(bar.getMenu(1).getItem(0))
        assertTrue(side.state)
        assertEquals(KeyStroke.getKeyStroke("ctrl M"), side.accelerator)
        val compared = assertIs<JMenu>(bar.getMenu(1).getItem(1))
        val choices = (0 until compared.itemCount).map { assertIs<JRadioButtonMenuItem>(compared.getItem(it)) }
        assertEquals(listOf(true, false), choices.map { it.isSelected })
    }

    @Test
    fun anEntryChosenDoesWhatItSays() {
        val bar = menuBar(menus())
        bar.getMenu(0).getItem(0).doClick()
        bar.getMenu(1).getItem(0).doClick()
        (bar.getMenu(1).getItem(1) as JMenu).getItem(1).doClick()
        assertEquals(listOf("open", "side by side false", "cat"), done)
    }

    @Test
    fun theBarIsBuiltAgainOnlyWhenWhatItShowsChanges() {
        assertEquals(shownOf(menus()), shownOf(menus()))
        assertNotEquals(shownOf(menus()), shownOf(menus(checked = false)))
    }
}
