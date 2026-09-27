package cz.loplex.dogvision.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts

/** The texts the screens are worded in, provided by the app or the window in its language. */
val LocalTexts = staticCompositionLocalOf<Texts> { error("No texts provided") }

/** The string [key] in the screens' language, as stringResource gives a resource. */
@Composable
@ReadOnlyComposable
fun text(key: Str, vararg args: Any?): String = LocalTexts.current.get(key, *args)
