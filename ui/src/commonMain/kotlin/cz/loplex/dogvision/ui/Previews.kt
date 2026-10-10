package cz.loplex.dogvision.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import cz.loplex.dogvision.core.CameraChoice
import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.Facing
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.texts.Texts

/** The controls in English, in the light colours the Compose window takes on a light system. */
@Preview(widthDp = 360, heightDp = 1400)
@Composable
private fun ControlsPreview() = PreviewedControls(Texts.of("en"), dark = false)

/** The controls in Czech, in the dark colours of the Android app. */
@Preview(widthDp = 360, heightDp = 1400)
@Composable
private fun CzechControlsPreview() = PreviewedControls(Texts.of("cs"), dark = true)

/** [Controls] worded in [texts], with a back camera and a webcam to choose from, each change shown as the app does. */
@Composable
private fun PreviewedControls(texts: Texts, dark: Boolean) {
    var view by remember { mutableStateOf(View()) }
    var camera by remember {
        val back = CameraOption("0", null, Facing.BACK)
        mutableStateOf(CameraChoice(listOf(back, CameraOption("1", "Integrated Camera", Facing.UNKNOWN)), shown = back))
    }
    var language by remember { mutableStateOf("") }
    CompositionLocalProvider(LocalTexts provides texts) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface {
                Controls(
                    view = view,
                    recording = false,
                    onChange = { change -> view = change(view) },
                    onReset = { view = View() },
                    camera = camera,
                    onCamera = { camera = camera.copy(shown = it) },
                    onMirroring = { camera = camera.copy(mirroring = it) },
                    language = language,
                    onLanguage = { language = it },
                )
            }
        }
    }
}
