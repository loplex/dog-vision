package cz.loplex.dogvision.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import cz.loplex.dogvision.texts.Menu
import cz.loplex.dogvision.texts.MenuEntry
import cz.loplex.dogvision.texts.MenuKey
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.shownAt

/**
 * A button of three dots, tinted [tint], that drops [menus] down as one list:
 * their names first, and in place of the list, the entries of a menu or a submenu chosen, under a row back up.\
 * An entry chosen does what it does and closes the list, as Material's menus do;
 * Material's menu has no submenus of its own that open beside it.\
 * Each entry's key is shown beside it where [keys], as a desktop window's menus show them.
 */
@Composable
fun MenuButton(menus: List<Menu>, tint: Color, modifier: Modifier = Modifier, keys: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(MORE_ICON, contentDescription = text(Str.MENU), tint = tint)
        }
        MenuList(menus, expanded, keys, onClose = { expanded = false })
    }
}

/**
 * [menus] dropped down while [expanded]: their names,
 * and in place of them, the entries of a menu or a submenu chosen, as [shownAt] finds it, under a row back up.\
 * An entry's key is shown beside it where [keys].\
 * [onClose] is told when it closes, as an entry chosen closes it.
 */
@Composable
@Suppress("MagicNumber")
private fun MenuList(menus: List<Menu>, expanded: Boolean, keys: Boolean, onClose: () -> Unit) {
    // The menu shown, as the place of each menu and submenu on the way to it.
    var path by remember { mutableStateOf(emptyList<Int>()) }
    fun close() {
        path = emptyList()
        onClose()
    }
    DropdownMenu(expanded = expanded, onDismissRequest = ::close) {
        val shown = shownAt(menus, path)
        if (shown == null) {
            menus.forEachIndexed { place, menu ->
                DropdownMenuItem(
                    text = { Text(menu.label) },
                    trailingIcon = { Icon(EXPAND_ICON, contentDescription = null, Modifier.rotate(-90f)) },
                    onClick = { path = listOf(place) },
                )
            }
        } else {
            DropdownMenuItem(
                text = { Text(shown.first) },
                leadingIcon = { Icon(EXPAND_ICON, contentDescription = text(Str.BACK), Modifier.rotate(90f)) },
                onClick = { path = path.dropLast(1) },
            )
            HorizontalDivider()
            shown.second.forEachIndexed { place, entry ->
                Entry(entry, keys, onClose = ::close, onOpen = { path = path + place })
            }
        }
    }
}

@Composable
@Suppress("MagicNumber")
private fun Entry(entry: MenuEntry, keys: Boolean, onClose: () -> Unit, onOpen: () -> Unit) {
    when (entry) {
        is MenuEntry.Action -> DropdownMenuItem(
            text = { Text(entry.label) },
            trailingIcon = keyOf(entry.key, keys),
            enabled = entry.enabled,
            onClick = {
                onClose()
                entry.onSelect()
            },
        )

        is MenuEntry.Check -> DropdownMenuItem(
            text = { Text(entry.label) },
            // The check box takes no clicks, so it says nothing to accessibility services: the row says it.
            modifier = Modifier.semantics {
                role = Role.Checkbox
                toggleableState = ToggleableState(entry.checked)
            },
            leadingIcon = { Checkbox(entry.checked, onCheckedChange = null, enabled = entry.enabled) },
            trailingIcon = keyOf(entry.key, keys),
            enabled = entry.enabled,
            onClick = {
                onClose()
                entry.onSelect(!entry.checked)
            },
        )

        is MenuEntry.Choice -> DropdownMenuItem(
            text = { Text(entry.label) },
            modifier = Modifier.semantics {
                role = Role.RadioButton
                selected = entry.selected
            },
            leadingIcon = { RadioButton(entry.selected, onClick = null, enabled = entry.enabled) },
            enabled = entry.enabled,
            onClick = {
                onClose()
                entry.onSelect()
            },
        )

        is MenuEntry.Submenu -> DropdownMenuItem(
            text = { Text(entry.label) },
            trailingIcon = { Icon(EXPAND_ICON, contentDescription = null, Modifier.rotate(-90f)) },
            enabled = entry.enabled,
            onClick = onOpen,
        )

        MenuEntry.Separator -> HorizontalDivider()
    }
}

/** The [key] shown beside an entry where [keys], as Ctrl+M, or nothing. */
private fun keyOf(key: MenuKey?, keys: Boolean): (@Composable () -> Unit)? =
    key?.takeIf { keys }?.let { { Text(if (it.withCtrl) "Ctrl+${it.key}" else it.key) } }
