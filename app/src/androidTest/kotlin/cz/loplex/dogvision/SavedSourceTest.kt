package cz.loplex.dogvision

import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The source shown comes back from a Bundle that went through a Parcel, as when Android ends the app
 * in the background.
 */
@RunWith(AndroidJUnit4::class)
class SavedSourceTest {
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

    private val picked = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/42")

    @Test
    fun aPhotoComesBack() {
        val photo = Source.Photo(picked, "IMG_0042.jpg")
        assertEquals(photo, parcelled(photo.toBundle()).toSource())
    }

    @Test
    fun aVideoComesBack() {
        val video = Source.Video(picked, "VID_0042.mp4")
        assertEquals(video, parcelled(video.toBundle()).toSource())
    }

    @Test
    fun theCameraComesBack() {
        assertEquals(Source.Camera, parcelled(Source.Camera.toBundle()).toSource())
    }

    @Test
    fun aKindMissingOrUnknownIsTheCamera() {
        val bundle = Source.Photo(picked, "IMG_0042.jpg").toBundle()
        assertEquals(Source.Camera, Bundle(bundle).apply { remove("kind") }.toSource())
        assertEquals(Source.Camera, Bundle(bundle).apply { putString("kind", "hologram") }.toSource())
        assertEquals(Source.Camera, Bundle().toSource())
    }
}
