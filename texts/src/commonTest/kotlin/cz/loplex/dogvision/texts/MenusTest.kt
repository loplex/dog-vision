package cz.loplex.dogvision.texts

import cz.loplex.dogvision.core.CameraChoice
import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.Facing
import cz.loplex.dogvision.core.Mirroring
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The menus hold what the panel holds, locked as it locks it, and its keys do what their entries do. */
class MenusTest {
    private val texts = Texts.of("en")
    private val front = CameraOption("0", null, Facing.FRONT)
    private val cameras = CameraChoice(listOf(front), front, Mirroring.AUTO)

    /** The actions, applied to a view of their own. */
    private class Actions(var view: View = View()) : PanelActions {
        var resets = 0
        var camera: CameraOption? = null
        var chosenMirroring: Mirroring? = null
        var chosenLanguage: String? = null

        override fun changeView(change: (View) -> View) {
            view = change(view)
        }

        override fun reset() {
            resets++
        }

        override fun chooseCamera(camera: CameraOption?) {
            this.camera = camera
        }

        override fun setMirroring(mirroring: Mirroring) {
            chosenMirroring = mirroring
        }

        override fun setLanguage(language: String) {
            chosenLanguage = language
        }
    }

    private fun menus(actions: Actions, recording: Boolean = false, language: String = "") =
        texts.panelMenus(PanelShown(actions.view, cameras, language, recording), actions)

    private fun List<Menu>.menu(label: String) = single { it.label == label }.entries

    private fun List<MenuEntry>.labelled(label: String) = single {
        when (it) {
            is MenuEntry.Action -> it.label == label
            is MenuEntry.Check -> it.label == label
            is MenuEntry.Choice -> it.label == label
            is MenuEntry.Submenu -> it.label == label
            MenuEntry.Separator -> false
        }
    }

    private fun List<MenuEntry>.submenu(label: String) = assertIs<MenuEntry.Submenu>(labelled(label))

    private fun List<MenuEntry>.choices() = filterIsInstance<MenuEntry.Choice>()

    @Test
    fun theMenusAreThePanelsSections() {
        val labels = menus(Actions()).map { it.label }
        assertEquals(listOf("Camera", "Species", "Simulation", "Acuity", "View", "Language"), labels)
    }

    @Test
    fun aSpeciesChosenIsShown() {
        val actions = Actions()
        val species = menus(actions).menu("Species").choices()
        assertEquals(Species.entries.map(texts::speciesLabel), species.map { it.label })
        assertEquals(listOf(texts.speciesLabel(Species.DOG)), species.filter { it.selected }.map { it.label })
        species.single { it.label == texts.speciesLabel(Species.CAT) }.onSelect()
        assertEquals(Species.CAT, actions.view.params.species)
    }

    @Test
    fun aSliderIsOfferedAtFixedSharesAndAnotherChoosesNone() {
        val actions = Actions(View(Params(adaptation = 0.25, strength = 0.3)))
        val simulation = menus(actions).menu("Simulation")
        val adaptation = simulation.submenu(texts.get(Str.ADAPTATION)).entries.choices()
        assertEquals(listOf("0%", "25%", "50%", "75%", "100%"), adaptation.map { it.label })
        assertEquals(listOf("25%"), adaptation.filter { it.selected }.map { it.label })
        assertTrue(simulation.submenu(texts.get(Str.STRENGTH)).entries.choices().none { it.selected })
        adaptation.last().onSelect()
        assertEquals(1.0, actions.view.params.adaptation)
    }

    @Test
    fun theFieldOfViewIsOfferedOnlyWithTheBlur() {
        val acuity = menus(Actions()).menu("Acuity")
        assertFalse(acuity.submenu(texts.get(Str.FIELD_OF_VIEW)).enabled)
        val actions = Actions(View(Params(acuity = true)))
        val degrees = menus(actions).menu("Acuity").submenu(texts.get(Str.FIELD_OF_VIEW))
        assertTrue(degrees.enabled)
        assertEquals(listOf("60°"), degrees.entries.choices().filter { it.selected }.map { it.label })
        degrees.entries.choices().first().onSelect()
        assertEquals(10.0, actions.view.params.fieldOfView)
    }

    @Test
    fun recordingLocksWhatWouldChangeTheViewsSize() {
        val actions = Actions(View(sideBySide = true))
        val free = menus(actions)
        val locked = menus(actions, recording = true)
        assertTrue(free.menu("Camera").choices().all { it.enabled || it.label.startsWith("Automatic") })
        assertTrue(locked.menu("Camera").choices().take(2).none { it.enabled })
        for (label in listOf(texts.get(Str.SIDE_BY_SIDE), texts.get(Str.DIFFERENCE))) {
            assertTrue(assertIs<MenuEntry.Check>(free.menu("View").labelled(label)).enabled, label)
            assertFalse(assertIs<MenuEntry.Check>(locked.menu("View").labelled(label)).enabled, label)
        }
        assertTrue(assertIs<MenuEntry.Action>(locked.menu("View").labelled(texts.get(Str.RESET))).enabled)
    }

    @Test
    fun theCameraAndItsMirroringAreChosen() {
        val actions = Actions()
        val camera = menus(actions).menu("Camera")
        assertEquals(listOf("Off", "Front camera"), camera.choices().take(2).map { it.label })
        camera.choices().first().onSelect()
        assertEquals(null, actions.camera)
        camera.choices().single { it.label == texts.get(Str.MIRROR) }.onSelect()
        assertEquals(Mirroring.MIRROR, actions.chosenMirroring)
    }

    @Test
    fun withoutSideBySideNothingIsComparedOrMapped() {
        val view = menus(Actions(View(sideBySide = false))).menu("View")
        assertFalse(view.submenu(texts.get(Str.COMPARE_WITH)).enabled)
        assertFalse(assertIs<MenuEntry.Check>(view.labelled(texts.get(Str.DIFFERENCE))).enabled)
    }

    @Test
    fun aKeyDoesWhatItsEntryDoesWhileItIsEnabled() {
        val actions = Actions(View(sideBySide = false))
        assertFalse(menus(actions).press(MenuKey.DIFFERENCE))
        assertTrue(menus(actions).press(MenuKey.SIDE_BY_SIDE))
        assertTrue(actions.view.sideBySide)
        assertTrue(menus(actions).press(MenuKey.DIFFERENCE))
        assertTrue(actions.view.difference)
        assertFalse(menus(actions, recording = true).press(MenuKey.SIDE_BY_SIDE))
        assertTrue(menus(actions).press(MenuKey.RESET))
        assertEquals(1, actions.resets)
        assertFalse(menus(actions).press(MenuKey.OPEN))
    }

    @Test
    fun aWindowsOwnEntriesGoAtTheTopOfView() {
        val own = MenuEntry.Check("Controls", true, MenuKey.CONTROLS) {}
        val view = texts.panelMenus(PanelShown(View(), cameras, ""), Actions(), listOf(own)).menu("View")
        assertEquals(listOf(own, MenuEntry.Separator), view.take(2))
    }

    @Test
    fun theLanguagesAreNamedInThemselves() {
        val actions = Actions()
        val languages = menus(actions, language = "cs").menu("Language").choices()
        assertEquals(listOf("As the system", "English", "Čeština"), languages.map { it.label })
        assertEquals(listOf("Čeština"), languages.filter { it.selected }.map { it.label })
        languages.first().onSelect()
        assertEquals("", actions.chosenLanguage)
    }
}
