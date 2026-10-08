package cz.loplex.dogvision.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import cz.loplex.dogvision.texts.Menu
import cz.loplex.dogvision.texts.MenuEntry
import cz.loplex.dogvision.texts.Texts
import kotlin.test.Test
import kotlin.test.assertEquals

/** The app's menu drops the menus down as one list, a menu's entries in its place, with a row back up. */
@OptIn(ExperimentalTestApi::class)
class MenusTest {
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
                MenuEntry.Submenu(
                    "Compared with",
                    listOf(
                        MenuEntry.Choice("dog", true) { done += "dog" },
                        MenuEntry.Choice("cat", false) { done += "cat" },
                    ),
                ),
            ),
        ),
    )

    private fun ComposeUiTest.show() = setContent {
        CompositionLocalProvider(LocalTexts provides Texts.of("en")) {
            MaterialTheme { MenuButton(menus, Color.Black) }
        }
    }

    @Test
    fun aMenuChosenShowsItsEntriesAndTheRowBackTheMenus() = runComposeUiTest {
        show()
        onNodeWithContentDescription("Menu").performClick()
        onNodeWithText("File").performClick()
        onNodeWithText("Cancel").assertIsNotEnabled()
        onNodeWithText("File").performClick()
        onNodeWithText("View").performClick()
        onNodeWithText("Compared with").performClick()
        onNodeWithText("cat").performClick()
        assertEquals(listOf("cat"), done)
        onNodeWithText("cat").assertDoesNotExist()
    }

    @Test
    fun aCheckOrAChoiceTellsAccessibilityServicesWhetherItIsChosen() = runComposeUiTest {
        show()
        onNodeWithContentDescription("Menu").performClick()
        onNodeWithText("View").performClick()
        onNodeWithText("Side by side").assertIsOn()
        onNodeWithText("Compared with").performClick()
        onNodeWithText("dog").assertIsSelected()
        onNodeWithText("cat").assertIsNotSelected()
    }

    @Test
    fun anEntryChosenDoesWhatItSaysAndClosesTheList() = runComposeUiTest {
        show()
        onNodeWithContentDescription("Menu").performClick()
        onNodeWithText("View").performClick()
        onNodeWithText("Side by side").performClick()
        onNodeWithContentDescription("Menu").performClick()
        onNodeWithText("File").performClick()
        onNodeWithText("Open").performClick()
        assertEquals(listOf("side by side false", "open"), done)
    }
}
