package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.texts.Menu
import cz.loplex.dogvision.texts.MenuEntry
import cz.loplex.dogvision.texts.MenuKey
import cz.loplex.dogvision.texts.PanelShown
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.panelMenus

/**
 * A window's menus, worded in [state]'s texts: File, which opens a file through [open],
 * shows the camera, saves a snapshot, picks the output folder through [chooseOutputDir] and quits through [quit],
 * then everything the panel holds, the controls' own toggle at the top of View.\
 * A window lays them out, and its keys press them.
 */
fun LiveSession<*>.menus(
    state: LiveSession.State,
    open: () -> Unit,
    chooseOutputDir: () -> Unit,
    quit: () -> Unit,
): List<Menu> {
    val texts = state.texts
    val file = Menu(
        texts.get(Str.MENU_FILE),
        listOf(
            MenuEntry.Action(texts.get(Str.OPEN_MEDIA_MENU), MenuKey.OPEN, onSelect = open),
            MenuEntry.Action(texts.get(Str.SHOW_CAMERA), onSelect = ::openCamera),
            MenuEntry.Separator,
            MenuEntry.Action(texts.get(Str.SAVE_SNAPSHOT), MenuKey.SNAPSHOT, onSelect = ::saveSnapshot),
            MenuEntry.Separator,
            MenuEntry.Action(texts.get(Str.OUTPUT_FOLDER_MENU), onSelect = chooseOutputDir),
            MenuEntry.Separator,
            MenuEntry.Action(texts.get(Str.QUIT), MenuKey.QUIT, onSelect = quit),
        ),
    )
    val controls = MenuEntry.Check(texts.get(Str.CONTROLS), state.panelShown, MenuKey.CONTROLS) { togglePanel() }
    val shown = PanelShown(state.view, state.camera, state.language)
    return listOf(file) + texts.panelMenus(shown, this, listOf(controls))
}
