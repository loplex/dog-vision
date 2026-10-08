package cz.loplex.dogvision.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import cz.loplex.dogvision.texts.Menu
import cz.loplex.dogvision.texts.MenuEntry
import cz.loplex.dogvision.texts.MenuKey

/** The keys of the menus' entries, as Compose names them. */
internal val MENU_KEYS = MenuKey.entries.associateBy { key ->
    when (key) {
        MenuKey.OPEN -> Key.O
        MenuKey.QUIT -> Key.Q
        MenuKey.SNAPSHOT -> Key.S
        MenuKey.RECORD -> Key.R
        MenuKey.CONVERT -> Key.E
        MenuKey.CONTROLS -> Key.F9
        MenuKey.RESET -> Key.Zero
        MenuKey.SIDE_BY_SIDE -> Key.M
        MenuKey.DIFFERENCE -> Key.D
    }
}

/** [menus] as the window's menu bar, each key shown beside its entry. */
@Composable
internal fun FrameWindowScope.WindowMenuBar(menus: List<Menu>) {
    MenuBar {
        menus.forEach { menu -> Menu(menu.label) { Entries(menu.entries) } }
    }
}

@Composable
private fun MenuScope.Entries(entries: List<MenuEntry>) {
    entries.forEach { entry ->
        when (entry) {
            is MenuEntry.Action ->
                Item(entry.label, enabled = entry.enabled, shortcut = shortcutOf(entry.key), onClick = entry.onSelect)

            is MenuEntry.Check -> CheckboxItem(
                entry.label,
                entry.checked,
                enabled = entry.enabled,
                shortcut = shortcutOf(entry.key),
                onCheckedChange = entry.onSelect,
            )

            is MenuEntry.Choice ->
                RadioButtonItem(entry.label, entry.selected, enabled = entry.enabled, onClick = entry.onSelect)

            is MenuEntry.Submenu -> Menu(entry.label, enabled = entry.enabled) { Entries(entry.entries) }

            MenuEntry.Separator -> Separator()
        }
    }
}

/** The shortcut [key] shows beside its entry, if it has one. */
private fun shortcutOf(key: MenuKey?): KeyShortcut? =
    key?.let { menuKey -> KeyShortcut(MENU_KEYS.entries.first { it.value == menuKey }.key, ctrl = menuKey.withCtrl) }
