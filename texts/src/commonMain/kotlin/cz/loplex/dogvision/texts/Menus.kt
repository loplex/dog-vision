package cz.loplex.dogvision.texts

import cz.loplex.dogvision.core.CameraChoice
import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Mirroring
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View

/**
 * A key that does what an entry of a menu does, shown beside it, as the desktop windows take it:
 * [key], with Ctrl where [withCtrl], as the desktop's programs take theirs.
 */
enum class MenuKey(val key: String, val withCtrl: Boolean = true) {
    /** Open, as everywhere. */
    OPEN("O"),

    /** Quit, as on Linux. */
    QUIT("Q"),

    /** Save, as everywhere: a snapshot. */
    SNAPSHOT("S"),

    /** Record, as recorders take it. */
    RECORD("R"),

    /** The controls at the side, as KDE's programs show and hide their side panel. */
    CONTROLS("F9", withCtrl = false),

    /** Back to the view the window started with, as Ctrl+0 resets a zoom. */
    RESET("0"),

    /** Side by side, which has no convention. */
    SIDE_BY_SIDE("M"),

    /** The map of differences, which has no convention. */
    DIFFERENCE("D"),
}

/** One entry of a menu, in no toolkit: a window's menu bar, the app's and the page's menus lay it out. */
sealed interface MenuEntry {
    /** Does [onSelect] while [enabled]; [key] does it too. */
    data class Action(
        val label: String,
        val key: MenuKey? = null,
        val enabled: Boolean = true,
        val onSelect: () -> Unit,
    ) : MenuEntry

    /** On or off, [checked] now; [onSelect] is handed the other while [enabled], and [key] hands it too. */
    data class Check(
        val label: String,
        val checked: Boolean,
        val key: MenuKey? = null,
        val enabled: Boolean = true,
        val onSelect: (Boolean) -> Unit,
    ) : MenuEntry

    /** One of the choices between two separators or in a submenu, [selected] now; [onSelect] chooses it. */
    data class Choice(
        val label: String,
        val selected: Boolean,
        val enabled: Boolean = true,
        val onSelect: () -> Unit,
    ) : MenuEntry

    /** A menu within the menu. */
    data class Submenu(val label: String, val entries: List<MenuEntry>, val enabled: Boolean = true) : MenuEntry

    /** A line between two groups of entries. */
    data object Separator : MenuEntry
}

/** A menu of a window's menu bar, or a part of the app's or the page's menu: its [label] and its [entries]. */
data class Menu(val label: String, val entries: List<MenuEntry>)

/**
 * Does what the entry [key] is shown beside does,
 * if one of these menus has it and it is enabled, as a menu's key does; returns whether it did.
 */
fun List<Menu>.press(key: MenuKey): Boolean {
    fun press(entries: List<MenuEntry>): Boolean = entries.any { entry ->
        when (entry) {
            is MenuEntry.Action -> (entry.key == key && entry.enabled).also { if (it) entry.onSelect() }
            is MenuEntry.Check -> (entry.key == key && entry.enabled).also { if (it) entry.onSelect(!entry.checked) }
            is MenuEntry.Submenu -> entry.enabled && press(entry.entries)
            is MenuEntry.Choice, MenuEntry.Separator -> false
        }
    }
    return any { press(it.entries) }
}

/** What the panel's controls change, which the menus that hold them change too. */
interface PanelActions {
    fun changeView(change: (View) -> View)

    fun reset()

    /** Shows [camera], or none for null, Off. */
    fun chooseCamera(camera: CameraOption?)

    fun setMirroring(mirroring: Mirroring)

    /** Speaks [language], a language tag, or the system's language for "". */
    fun setLanguage(language: String)
}

/**
 * What the panel shows, which its menus show too: [view], [camera], [language], a language tag or "" for the system's,
 * and whether the view is [recording], which locks what would change its size.
 */
data class PanelShown(val view: View, val camera: CameraChoice, val language: String, val recording: Boolean = false)

/** The shares a menu offers of a slider in percent, which still sets any other. */
val MENU_PERCENTS = listOf(0, 25, 50, 75, 100)

/** The angles a menu offers of the field of view, which the slider sets from 10° to 120°. */
val MENU_DEGREES = listOf(10, 30, 60, 90, 120)

/** A whole in percent. */
private const val WHOLE = 100.0

/**
 * Everything the panel holds, as menus worded in these texts:
 * Camera, Species, Simulation, Acuity, View and Language, showing what [shown] says and changing it through [actions].\
 * A slider's menu offers fixed values, none of them chosen where the slider set another.
 *
 * While recording, what would change the size of the view is disabled, as the panel locks it:
 * the camera, side by side and the map of differences.\
 * [viewFirst] is put at the top of View, above a separator: a window's own entries.
 */
fun Texts.panelMenus(shown: PanelShown, actions: PanelActions, viewFirst: List<MenuEntry> = emptyList()): List<Menu> {
    val params = shown.view.params
    fun setParams(change: Params.() -> Params) = actions.changeView { it.copy(params = it.params.change()) }
    return listOf(
        Menu(get(Str.CAMERA), cameraEntries(shown.camera, shown.recording, actions)),
        Menu(
            get(Str.SPECIES),
            Species.entries.map { species ->
                MenuEntry.Choice(speciesLabel(species), params.species == species) {
                    setParams { copy(species = species) }
                }
            },
        ),
        Menu(
            get(Str.SIMULATION),
            listOf(
                percents(get(Str.ADAPTATION), params.adaptation) { setParams { copy(adaptation = it) } },
                percents(get(Str.STRENGTH), params.strength) { setParams { copy(strength = it) } },
                MenuEntry.Submenu(
                    get(Str.COLOUR_SATURATION),
                    ChromaScale.entries.map { scale ->
                        val label = get(if (scale == ChromaScale.FIXED) Str.CHROMA_FIXED else Str.CHROMA_RNL)
                        MenuEntry.Choice(label, params.chromaScale == scale) { setParams { copy(chromaScale = scale) } }
                    },
                ),
            ),
        ),
        Menu(
            get(Str.ACUITY),
            listOf(
                MenuEntry.Check(get(Str.ACUITY_BLUR), params.acuity) { on -> setParams { copy(acuity = on) } },
                MenuEntry.Submenu(
                    get(Str.FIELD_OF_VIEW),
                    MENU_DEGREES.map { degrees ->
                        MenuEntry.Choice(get(Str.DEGREES, degrees), params.fieldOfView == degrees.toDouble()) {
                            setParams { copy(fieldOfView = degrees.toDouble()) }
                        }
                    },
                    enabled = params.acuity,
                ),
            ),
        ),
        Menu(
            get(Str.VIEW),
            viewFirst + listOfNotNull(MenuEntry.Separator.takeIf { viewFirst.isNotEmpty() }) +
                viewEntries(shown.view, shown.recording, actions),
        ),
        Menu(
            get(Str.LANGUAGE),
            (listOf("" to get(Str.SYSTEM_LANGUAGE)) + Texts.LANGUAGES.map { it to Texts.of(it).get(Str.LANGUAGE_NAME) })
                .map { (tag, name) -> MenuEntry.Choice(name, shown.language == tag) { actions.setLanguage(tag) } },
        ),
    )
}

/** The cameras [choice] offers, Off first, while not [recording], then the mirroring of the image. */
private fun Texts.cameraEntries(choice: CameraChoice, recording: Boolean, actions: PanelActions): List<MenuEntry> {
    val cameras = choice.offered.zip(cameraNames(choice)) { camera, name ->
        MenuEntry.Choice(name, choice.shown == camera, enabled = !recording) { actions.chooseCamera(camera) }
    }
    val mirrorings = listOf(
        Mirroring.MIRROR to get(Str.MIRROR),
        Mirroring.PLAIN to get(Str.DO_NOT_MIRROR),
        Mirroring.AUTO to automaticMirroring(choice.facing),
    ).map { (mirroring, name) ->
        val enabled = mirroring != Mirroring.AUTO || choice.automaticAvailable
        MenuEntry.Choice(name, choice.shownMirroring == mirroring, enabled) { actions.setMirroring(mirroring) }
    }
    return cameras + MenuEntry.Separator + mirrorings
}

/** Side by side, what is compared and the map of differences, as the panel's View has them, then Reset. */
private fun Texts.viewEntries(view: View, recording: Boolean, actions: PanelActions): List<MenuEntry> = listOf(
    MenuEntry.Check(get(Str.SIDE_BY_SIDE), view.sideBySide, MenuKey.SIDE_BY_SIDE, enabled = !recording) { on ->
        actions.changeView { it.copy(sideBySide = on) }
    },
    MenuEntry.Submenu(
        get(Str.COMPARE_WITH),
        (listOf(null) + Species.entries).map { species ->
            MenuEntry.Choice(species?.let(::speciesLabel) ?: get(Str.ORIGINAL), view.compare == species) {
                actions.changeView { it.copy(compare = species) }
            }
        },
        enabled = view.sideBySide,
    ),
    MenuEntry.Check(
        get(Str.DIFFERENCE),
        view.difference,
        MenuKey.DIFFERENCE,
        enabled = view.sideBySide && !recording,
    ) { on ->
        actions.changeView { it.copy(difference = on) }
    },
    MenuEntry.Separator,
    MenuEntry.Action(get(Str.RESET), MenuKey.RESET, onSelect = actions::reset),
)

/** A slider in percent named [label] at [share], as [MENU_PERCENTS] fixed shares, each handed to [onChoose]. */
private fun Texts.percents(label: String, share: Double, onChoose: (Double) -> Unit) = MenuEntry.Submenu(
    label,
    MENU_PERCENTS.map { percent ->
        MenuEntry.Choice(get(Str.PERCENT, percent), share == percent / WHOLE) { onChoose(percent / WHOLE) }
    },
)
