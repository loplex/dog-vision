package cz.loplex.dogvision.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared controls, shown in Compose's test scene as the app and the desktop window show them. */
@OptIn(ExperimentalTestApi::class)
class ControlsTest {
    private val english = Texts.of("en")

    /** What the controls change, held as the app's ViewModel and the desktop window hold it. */
    private class Held(view: View) {
        var view by mutableStateOf(view)
        var language by mutableStateOf("")
        var resets = 0
    }

    private fun ComposeUiTest.show(view: View = View(), recording: Boolean = false, texts: Texts = english): Held {
        val held = Held(view)
        setContent {
            CompositionLocalProvider(LocalTexts provides texts) {
                MaterialTheme {
                    Controls(
                        view = held.view,
                        recording = recording,
                        onChange = { change -> held.view = change(held.view) },
                        onReset = { held.resets++ },
                        language = held.language,
                        onLanguage = { held.language = it },
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
}
