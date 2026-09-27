package cz.loplex.dogvision

import android.content.Context
import cz.loplex.dogvision.texts.Texts

/** The texts in the language of this context's configuration, as its getString would pick a resource. */
val Context.texts: Texts
    get() = Texts.forLanguages(resources.configuration.locales.toLanguageTags().split(','))
