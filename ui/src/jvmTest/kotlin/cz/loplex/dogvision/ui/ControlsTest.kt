package cz.loplex.dogvision.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.loplex.dogvision.core.CameraChoice
import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.Facing
import cz.loplex.dogvision.core.Mirroring
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared controls, shown in Compose's test scene as the app and the Compose window show them. */
@OptIn(ExperimentalTestApi::class)
class ControlsTest {
    private val english = Texts.of("en")

    /** What the controls change, held as the app's ViewModel and gui-core's `LiveSession` hold it. */
    private class Held(view: View, camera: CameraChoice) {
        var view by mutableStateOf(view)
        var camera by mutableStateOf(camera)
        var language by mutableStateOf("")
        var resets = 0
    }

    private val back = CameraOption("0", null, Facing.BACK)
    private val webcam = CameraOption("1", "Integrated Camera", Facing.UNKNOWN)

    private fun ComposeUiTest.show(
        view: View = View(),
        recording: Boolean = false,
        texts: Texts = english,
        camera: CameraChoice = CameraChoice(listOf(back, webcam), shown = back),
        width: Dp? = null,
    ): Held {
        val held = Held(view, camera)
        setContent {
            CompositionLocalProvider(LocalTexts provides texts) {
                MaterialTheme {
                    Controls(
                        view = held.view,
                        recording = recording,
                        onChange = { change -> held.view = change(held.view) },
                        onReset = { held.resets++ },
                        camera = held.camera,
                        onCamera = { held.camera = held.camera.copy(shown = it) },
                        onMirroring = { held.camera = held.camera.copy(mirroring = it) },
                        language = held.language,
                        onLanguage = { held.language = it },
                        modifier = width?.let { Modifier.width(it) } ?: Modifier,
                    )
                }
            }
        }
        return held
    }

    private fun ComposeUiTest.node(key: Str) = onNodeWithText(english.get(key))

    @Test
    fun aSectionStartsOpenOnlyWhenTheViewUsesIt() = runComposeUiTest {
        show()
        node(Str.ADAPTATION).assertIsDisplayed()
        node(Str.ACUITY_BLUR).assertDoesNotExist()
        node(Str.SIDE_BY_SIDE).assertDoesNotExist()
        node(Str.VIEW).performScrollTo().performClick()
        node(Str.SIDE_BY_SIDE).assertExists()
        node(Str.SIMULATION).performClick()
        node(Str.ADAPTATION).assertDoesNotExist()
    }

    @Test
    fun theSectionsOfWhatTheViewUsesStartOpen() = runComposeUiTest {
        show(View(Params(acuity = true), difference = true))
        node(Str.ACUITY_BLUR).assertExists()
        node(Str.SIDE_BY_SIDE).assertExists()
    }

    @Test
    fun theFactsFollowTheSelectedSpecies() = runComposeUiTest {
        val held = show()
        onNodeWithText(english.speciesLabel(Species.DOG)).assertExists()
        node(Str.FACT_NEUTRAL_POINT).assertExists()
        node(Str.FACT_L_TO_M).assertDoesNotExist()
        held.view = View(Params(Species.HUMAN))
        node(Str.FACT_NEUTRAL_POINT).assertDoesNotExist()
        node(Str.FACT_L_TO_M).assertExists()
    }

    @Test
    fun aSourceTooWideForItsColumnBreaksOnlyAtItsSpaces() = runComposeUiTest {
        // The app's panel in landscape: the cone peaks' citation is wider than the value's column.
        show(width = 360.dp)
        val values = onAllNodes(hasText("1989", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        val layouts = values.map { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!(results)
            results.single()
        }
        assertTrue(layouts.any { it.lineCount > 1 }, "no citation needed a second line")
        layouts.forEach { layout ->
            val text = layout.layoutInput.text.text
            (0 until layout.lineCount - 1).forEach { line ->
                val end = layout.getLineEnd(line)
                assertTrue(text[end - 1].isWhitespace(), "\"$text\" broken after \"${text.substring(0, end)}\"")
            }
        }
    }

    @Test
    fun aSliderSetsAShareInWholePercent() = runComposeUiTest {
        val held = show()
        val sliders = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        sliders[0].performSemanticsAction(SemanticsActions.SetProgress) { it(25f) }
        assertEquals(0.25, held.view.params.adaptation)
        sliders[1].performSemanticsAction(SemanticsActions.SetProgress) { it(40.4f) }
        assertEquals(0.4, held.view.params.strength)
    }

    @Test
    fun recordingLocksWhatChangesHowManyImagesTheViewHas() = runComposeUiTest {
        show(View(difference = true), recording = true)
        node(Str.SIDE_BY_SIDE).assertIsNotEnabled()
        node(Str.DIFFERENCE).assertIsNotEnabled()
        onNodeWithText(english.get(Str.ORIGINAL)).assertIsEnabled()
    }

    @Test
    fun theLanguageChoiceNamesEachLanguageInItself() = runComposeUiTest {
        val held = show(texts = Texts.of("cs"))
        onNodeWithText("Podle systému").performScrollTo().performClick()
        onNodeWithText("English").assertExists()
        onNodeWithText("Čeština").performClick()
        assertEquals("cs", held.language)
        onNodeWithText("Čeština").assertExists()
        onNodeWithText("Podle systému").assertDoesNotExist()
    }

    @Test
    fun anInfoButtonShowsWhatItsControlMeans() = runComposeUiTest {
        show()
        node(Str.ABOUT_SPECIES).assertDoesNotExist()
        onAllNodesWithContentDescription(english.get(Str.ABOUT)).onFirst().performClick()
        node(Str.ABOUT_SPECIES).assertIsDisplayed()
        node(Str.CLOSE).performClick()
        node(Str.ABOUT_SPECIES).assertDoesNotExist()
    }

    @Test
    fun resetIsHandedOn() = runComposeUiTest {
        val held = show()
        node(Str.RESET).performScrollTo().performClick()
        assertEquals(1, held.resets)
    }

    @Test
    fun theCameraSectionOffersOffAndTheCamerasByName() = runComposeUiTest {
        val held = show()
        node(Str.CAMERA).performClick()
        node(Str.BACK_CAMERA).performClick()
        node(Str.CAMERA_OFF).assertExists()
        onNodeWithText("Integrated Camera").performClick()
        assertEquals(webcam, held.camera.shown)
        onNodeWithText("Integrated Camera").performClick()
        node(Str.CAMERA_OFF).performClick()
        assertEquals(null, held.camera.shown)
    }

    @Test
    fun automaticSaysWhereTheCameraFacesAndCannotBeChosenWhereThatIsUnknown() = runComposeUiTest {
        val held = show()
        node(Str.CAMERA).performClick()
        onNodeWithText("Automatic (back)").assertIsSelected().assertIsEnabled()
        node(Str.DO_NOT_MIRROR).performClick()
        assertEquals(Mirroring.PLAIN, held.camera.mirroring)
        held.camera = held.camera.copy(shown = webcam, mirroring = Mirroring.AUTO)
        node(Str.MIRROR_AUTOMATIC).assertIsNotEnabled().assertIsNotSelected()
        node(Str.MIRROR).assertIsSelected()
    }

    @Test
    fun recordingLocksTheChoiceOfCameraButNotItsMirroring() = runComposeUiTest {
        show(recording = true)
        node(Str.CAMERA).performClick()
        node(Str.BACK_CAMERA).assertIsNotEnabled()
        node(Str.MIRROR).assertIsEnabled()
    }
}
