package cz.loplex.dogvision.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.time.Duration.Companion.minutes

/**
 * Compose's runComposeUiTest with two minutes for a test in place of its one:
 * a test that passes alone at once ran out of the minute while ./gradlew check built and tested other modules at once.
 * A hang still fails, after two.
 */
@OptIn(ExperimentalTestApi::class)
fun runUiTest(block: suspend ComposeUiTest.() -> Unit) = runComposeUiTest(testTimeout = 2.minutes, block = block)
