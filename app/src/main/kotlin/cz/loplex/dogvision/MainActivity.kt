package cz.loplex.dogvision

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import cz.loplex.dogvision.texts.Texts
import cz.loplex.dogvision.ui.LocalTexts
import cz.loplex.dogvision.ui.MainScreen

class MainActivity : AppCompatActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // The configuration's languages, which the app's language choice sets, as resources would follow them.
            val languages = LocalConfiguration.current.locales.toLanguageTags()
            val texts = remember(languages) { Texts.forLanguages(languages.split(',')) }
            CompositionLocalProvider(LocalTexts provides texts) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    MainScreen(model)
                }
            }
        }
    }
}
