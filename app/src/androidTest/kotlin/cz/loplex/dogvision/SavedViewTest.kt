package cz.loplex.dogvision

import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The controls come back from a Bundle that went through a Parcel, as when Android ends the app in the background. */
@RunWith(AndroidJUnit4::class)
class SavedViewTest {
    /** [bundle] written to a Parcel and read back, as the system keeps it for a process it ended. */
    private fun parcelled(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        try {
            bundle.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return Bundle.CREATOR.createFromParcel(parcel).apply { classLoader = javaClass.classLoader }
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun everyControlAwayFromItsDefaultComesBack() {
        val view = View(
            params = Params(
                species = Species.CAT,
                adaptation = 0.4,
                strength = 0.7,
                chromaScale = ChromaScale.RNL,
                acuity = true,
                fieldOfView = 35.0,
            ),
            sideBySide = true,
            compare = Species.HORSE,
            difference = true,
        )
        assertEquals(view, parcelled(view.toBundle()).toView())
    }

    @Test
    fun theDefaultsComeBack() {
        assertEquals(View(), parcelled(View().toBundle()).toView())
        assertEquals(View(sideBySide = false), parcelled(View(sideBySide = false).toBundle()).toView())
    }

    @Test
    fun aControlMissingOrUnknownIsAtItsDefault() {
        val bundle = View(params = Params(strength = 0.5)).toBundle().apply {
            putString("species", "UNICORN")
            remove("acuity")
        }
        assertEquals(View(params = Params(strength = 0.5)), bundle.toView())
    }
}
