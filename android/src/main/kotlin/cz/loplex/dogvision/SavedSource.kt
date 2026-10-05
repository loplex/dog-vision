package cz.loplex.dogvision

import android.net.Uri
import android.os.Bundle

/** The source shown as a Bundle, which Android keeps while it ends the app in the background. */
internal fun Source.toBundle(): Bundle = Bundle().apply {
    when (this@toBundle) {
        Source.Camera -> putString(KIND, CAMERA)

        Source.Off -> putString(KIND, OFF)

        is Source.Photo -> {
            putString(KIND, PHOTO)
            putString(URI, uri.toString())
            putString(NAME, name)
        }

        is Source.Video -> {
            putString(KIND, VIDEO)
            putString(URI, uri.toString())
            putString(NAME, name)
        }
    }
}

/** The source [toBundle] kept; the camera, where it kept none it knows. */
internal fun Bundle.toSource(): Source {
    if (getString(KIND) == OFF) return Source.Off
    val uri = getString(URI)?.let(Uri::parse) ?: return Source.Camera
    val name = getString(NAME) ?: uri.lastPathSegment.orEmpty()
    return when (getString(KIND)) {
        PHOTO -> Source.Photo(uri, name)
        VIDEO -> Source.Video(uri, name)
        else -> Source.Camera
    }
}

private const val KIND = "kind"
private const val URI = "uri"
private const val NAME = "name"
private const val CAMERA = "camera"
private const val OFF = "off"
private const val PHOTO = "photo"
private const val VIDEO = "video"
