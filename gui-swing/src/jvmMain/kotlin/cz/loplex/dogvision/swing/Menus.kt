package cz.loplex.dogvision.swing

import cz.loplex.dogvision.texts.Menu
import cz.loplex.dogvision.texts.MenuEntry
import cz.loplex.dogvision.texts.MenuKey
import javax.swing.ButtonGroup
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JRadioButtonMenuItem
import javax.swing.KeyStroke

/**
 * [menus] as a menu bar, each key shown beside its entry.\
 * The window's own bindings take the keys before the bar's accelerators would,
 * as Swing gives a menu bar a key only when nothing else in the window took it.
 */
internal fun menuBar(menus: List<Menu>): JMenuBar = JMenuBar().apply {
    for ((label, entries) in menus) add(JMenu(label).apply { addEntries(entries) })
}

/** The key as Swing names it. */
internal val MenuKey.keyStroke: KeyStroke get() = KeyStroke.getKeyStroke(if (withCtrl) "ctrl $key" else key)

/** Adds [entries], each run of choices one group, of which one is selected at a time. */
private fun JMenu.addEntries(entries: List<MenuEntry>) {
    var group: ButtonGroup? = null
    for (entry in entries) {
        if (entry !is MenuEntry.Choice) group = null
        when (entry) {
            is MenuEntry.Action -> add(
                JMenuItem(entry.label).apply {
                    accelerator = entry.key?.keyStroke
                    isEnabled = entry.enabled
                    addActionListener { entry.onSelect() }
                },
            )

            is MenuEntry.Check -> add(
                JCheckBoxMenuItem(entry.label, entry.checked).apply {
                    accelerator = entry.key?.keyStroke
                    isEnabled = entry.enabled
                    addActionListener { entry.onSelect(!entry.checked) }
                },
            )

            is MenuEntry.Choice -> {
                val item = JRadioButtonMenuItem(entry.label, entry.selected).apply {
                    isEnabled = entry.enabled
                    addActionListener { entry.onSelect() }
                }
                (group ?: ButtonGroup().also { group = it }).add(item)
                add(item)
            }

            is MenuEntry.Submenu -> add(
                JMenu(entry.label).apply {
                    isEnabled = entry.enabled
                    addEntries(entry.entries)
                },
            )

            MenuEntry.Separator -> addSeparator()
        }
    }
}
