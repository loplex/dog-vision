package cz.loplex.dogvision.web

import cz.loplex.dogvision.texts.Menu
import cz.loplex.dogvision.texts.MenuEntry
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import cz.loplex.dogvision.texts.shownAt
import cz.loplex.dogvision.texts.shownOf
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.KeyboardEvent

/**
 * The page's menu: [button] drops [list] down under it, the menus' names first,
 * and in place of them the entries of a menu or a submenu chosen, under a row back up, as the app's menu does.\
 * An entry chosen does what it does and closes the list, and so do a click elsewhere and Escape.\
 * The list is built again only when what the menus show changes.
 */
class MenuList(private val button: HTMLButtonElement, private val list: HTMLElement) {
    private var menus = emptyList<Menu>()
    private var texts = Texts.of("en")
    private var shown: Any? = null

    /** The menu shown, as the place of each menu and submenu on the way to it. */
    private var path = emptyList<Int>()

    private val open get() = !list.hidden

    init {
        button.addEventListener("click", { if (open) close() else show(emptyList()) })
        document.addEventListener("click", { event ->
            val target = event.target as? Node ?: return@addEventListener
            if (open && !button.contains(target) && !list.contains(target)) close()
        })
        document.addEventListener("keydown", { event ->
            if (open && (event as KeyboardEvent).key == "Escape") close()
        })
        close()
    }

    /** Holds [menus], worded by [texts], shown at once if they are dropped down and what they show has changed. */
    fun show(menus: List<Menu>, texts: Texts) {
        this.menus = menus
        this.texts = texts
        button.title = texts.get(Str.MENU)
        button.setAttribute("aria-label", texts.get(Str.MENU))
        val now = shownOf(menus) to path
        if (open && now != shown) render()
    }

    private fun show(path: List<Int>) {
        this.path = path
        list.hidden = false
        button.setAttribute("aria-expanded", "true")
        render()
    }

    private fun close() {
        path = emptyList()
        list.hidden = true
        button.setAttribute("aria-expanded", "false")
        shown = null
    }

    private fun render() {
        shown = shownOf(menus) to path
        list.innerHTML = ""
        val at = shownAt(menus, path)
        if (at == null) {
            menus.forEachIndexed { place, menu -> item(menu.label, "menuitem", trailing = "›") { show(listOf(place)) } }
            return
        }
        item(at.first, "menuitem", leading = "‹", label = texts.get(Str.BACK)) { show(path.dropLast(1)) }
        list.appendChild(separator())
        at.second.forEachIndexed { place, entry -> entry(entry) { show(path + place) } }
    }

    private fun entry(entry: MenuEntry, onOpen: () -> Unit) {
        when (entry) {
            is MenuEntry.Action -> item(entry.label, "menuitem", enabled = entry.enabled) {
                close()
                entry.onSelect()
            }

            is MenuEntry.Check -> item(entry.label, "menuitemcheckbox", entry.enabled, checked = entry.checked) {
                close()
                entry.onSelect(!entry.checked)
            }

            is MenuEntry.Choice -> item(entry.label, "menuitemradio", entry.enabled, checked = entry.selected) {
                close()
                entry.onSelect()
            }

            is MenuEntry.Submenu -> item(entry.label, "menuitem", entry.enabled, trailing = "›", onClick = onOpen)

            MenuEntry.Separator -> list.appendChild(separator())
        }
    }

    /**
     * Adds a row of [text] in [role], with a [leading] and a [trailing] mark, a check where [checked] says,
     * and [label] for assistive technology where the text alone does not say what it does.
     */
    @Suppress("LongParameterList")
    private fun item(
        text: String,
        role: String,
        enabled: Boolean = true,
        checked: Boolean? = null,
        leading: String? = null,
        trailing: String? = null,
        label: String? = null,
        onClick: () -> Unit,
    ) {
        val row = (document.createElement("button") as HTMLButtonElement).apply {
            type = "button"
            className = "menu-item"
            setAttribute("role", role)
            checked?.let { setAttribute("aria-checked", "$it") }
            label?.let { setAttribute("aria-label", "$it: $text") }
            disabled = !enabled
            textContent = listOfNotNull(leading ?: checked?.let { if (it) "✓" else "" }, text).joinToString(" ").trim()
            trailing?.let { appendChild((document.createElement("span") as HTMLElement).apply { textContent = it }) }
            addEventListener("click", { event ->
                event.stopPropagation()
                onClick()
            })
        }
        list.appendChild(row)
    }

    private fun separator() = (document.createElement("hr") as HTMLElement).apply { setAttribute("role", "separator") }
}
