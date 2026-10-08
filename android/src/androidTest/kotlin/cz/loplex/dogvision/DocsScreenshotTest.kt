package cz.loplex.dogvision

import android.app.LocaleManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Takes the documents' screenshot of the app, `docs/images/android.png`:
 * the apple photo open, in English, as [MainActivity] shows it, without the system's bars.\
 * It goes as `android.png` into the instrumentation's additional output,
 * which `connectedDebugAndroidTest` pulls into `android/build/outputs/connected_android_test_additional_output`
 * and `tools/take_screenshots.sh` scales into the documents.\
 * As a test, it holds that the photo opens and is drawn.
 *
 * The app's language is set as its Language choice sets it, and put back as it was after.
 */
@RunWith(AndroidJUnit4::class)
class DocsScreenshotTest {
    @Test
    fun theApplePhotoOpenInEnglish() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val photo = context.cacheDir.resolve(PHOTO)
        instrumentation.context.assets.open(PHOTO).use { asset -> photo.outputStream().use(asset::copyTo) }
        val languagesBefore = appLanguages(context)
        setAppLanguages(context, "en")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.waitUntil("the app in English") {
                    it.resources.configuration.locales.toLanguageTags().startsWith("en")
                }
                lateinit var model: MainViewModel
                scenario.onActivity {
                    model = ViewModelProvider(it)[MainViewModel::class.java]
                    model.openMedia(Uri.fromFile(photo))
                }
                scenario.waitUntil("the photo shown") { model.source.value is Source.Photo }
                var insets = WindowInsetsCompat.CONSUMED
                scenario.onActivity {
                    insets = ViewCompat.getRootWindowInsets(it.window.decorView) ?: WindowInsetsCompat.CONSUMED
                }
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val screen = settledScreenshot()
                val height = screen.height - bars.top - bars.bottom
                val shot = Bitmap.createBitmap(screen, 0, bars.top, screen.width, height)
                assertTrue("the photo's area is drawn", shot.isNotUniform())
                val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
                File(output).resolve(FILE).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally {
            setAppLanguages(context, languagesBefore)
        }
    }

    /** The screen once two captures in a row agree: the photo's frame is drawn after the source changes. */
    private fun settledScreenshot(): Bitmap {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var last = automation.takeScreenshot().copy(Bitmap.Config.ARGB_8888, false)
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            val next = automation.takeScreenshot().copy(Bitmap.Config.ARGB_8888, false)
            if (next.sameAs(last)) return next
            last = next
        }
        fail("the screen kept changing for $TIMEOUT_MS ms")
        return last
    }

    private fun <A : android.app.Activity> ActivityScenario<A>.waitUntil(what: String, condition: (A) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            var met = false
            onActivity { met = condition(it) }
            if (met) return
            if (System.currentTimeMillis() > deadline) fail("$what did not come within $TIMEOUT_MS ms")
            Thread.sleep(POLL_MS)
        }
    }

    /** Whether the pixels of a coarse grid over the bitmap differ in colour: a photo does, a blank screen does not. */
    private fun Bitmap.isNotUniform(): Boolean {
        val colours = HashSet<Int>()
        for (y in 0 until height step GRID_STEP) for (x in 0 until width step GRID_STEP) colours.add(getPixel(x, y))
        return colours.size > UNIFORM_COLOURS
    }

    private fun appLanguages(context: Context): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    } else {
        AppCompatDelegate.getApplicationLocales().toLanguageTags()
    }

    /**
     * Sets the app's language before its activity starts,
     * where [AppCompatDelegate] does nothing yet on Android 13 and later:
     * there the system holds the choice, and the activity starts in it.
     */
    private fun setAppLanguages(context: Context, tags: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tags)
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
        }
    }

    private companion object {
        const val PHOTO = "shiny-red-apples.jpg"
        const val FILE = "android.png"
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 250L
        const val GRID_STEP = 40
        const val UNIFORM_COLOURS = 16
    }
}
