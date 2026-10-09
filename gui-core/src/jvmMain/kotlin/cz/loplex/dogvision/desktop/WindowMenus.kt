package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.texts.Menu
import cz.loplex.dogvision.texts.MenuEntry
import cz.loplex.dogvision.texts.MenuKey
import cz.loplex.dogvision.texts.PanelShown
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.panelMenus

/**
 * A window's menus, worded in [state]'s texts: File, which opens a file through [open],
 * shows the camera, saves a snapshot, records, converts the file shown,
 * picks the output folder through [chooseOutputDir], says where a converted file goes and quits through [quit],
 * then everything the panel holds, the toggles of the controls and of the menu bar at the top of View.\
 * While recording, what would change the video's size is disabled.\
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
            MenuEntry.Action(texts.get(Str.OPEN_MEDIA_MENU), MenuKey.OPEN, !state.recording, onSelect = open),
            MenuEntry.Action(texts.get(Str.SHOW_CAMERA), enabled = !state.recording, onSelect = ::openCamera),
            MenuEntry.Separator,
            MenuEntry.Action(texts.get(Str.SAVE_SNAPSHOT), MenuKey.SNAPSHOT, onSelect = ::saveSnapshot),
            MenuEntry.Check(texts.get(Str.RECORD), state.recording, MenuKey.RECORD) { toggleRecording() },
            MenuEntry.Separator,
            MenuEntry.Action(
                texts.get(Str.CONVERT_FILE),
                MenuKey.CONVERT,
                state.source is Source.Media && !state.converting,
                onSelect = ::convertSource,
            ),
            MenuEntry.Action(
                texts.get(Str.CANCEL_CONVERSION),
                enabled = state.converting,
                onSelect = ::cancelConversion,
            ),
            MenuEntry.Separator,
            MenuEntry.Action(texts.get(Str.OUTPUT_FOLDER_MENU), onSelect = chooseOutputDir),
            MenuEntry.Check(texts.get(Str.CONVERT_TO_OUTPUT_DIR), state.convertToOutputDir) {
                setConvertToOutputDir(it)
            },
            MenuEntry.Separator,
            MenuEntry.Action(texts.get(Str.QUIT), MenuKey.QUIT, onSelect = quit),
        ),
    )
    val controls = MenuEntry.Check(texts.get(Str.CONTROLS), state.panelShown, MenuKey.CONTROLS) { togglePanel() }
    val menuBar = MenuEntry.Check(texts.get(Str.MENU_BAR), state.menuBarShown, MenuKey.MENU_BAR) { toggleMenuBar() }
    val shown = PanelShown(state.view, state.camera, state.language, state.recording)
    return listOf(file) + texts.panelMenus(shown, this, listOf(controls, menuBar))
}
