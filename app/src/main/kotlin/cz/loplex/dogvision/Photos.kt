package cz.loplex.dogvision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import cz.loplex.dogvision.core.exifTurn
import cz.loplex.dogvision.render.Frame
import kotlin.math.max
import kotlin.math.roundToInt

/** The longest side a photo is shown at, which keeps the controls quick; saving it keeps its size. */
const val PREVIEW_LONGEST_SIDE = 1280

/** The name a document provider gives the file at [uri], or its last path segment. */
fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: uri.toString()

/**
 * The photo at [uri] as sRGB, no larger than [longestSide] if it is given, and turned as its EXIF
 * orientation says; null if it is not an image.
 */
fun decodePhoto(context: Context, uri: Uri, longestSide: Int? = null): Pair<Bitmap, Pair<Int, Boolean>>? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    if (longestSide != null) {
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longestSide) sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val scale = if (longestSide == null) 1.0 else longestSide.toDouble() / max(decoded.width, decoded.height)
    val bitmap = if (scale >= 1) {
        decoded
    } else {
        val width = (decoded.width * scale).roundToInt()
        val height = (decoded.height * scale).roundToInt()
        decoded.scale(width, height).also { decoded.recycle() }
    }
    val orientation = resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    return bitmap to exifTurn(orientation)
}

/**
 * A bitmap's pixels as a frame, turned as [turn] says: each pixel's colour as it is stored, whatever its alpha, as the
 * web page and core's pipeline for a photo saved at full size take it.
 */
fun Bitmap.toFrame(turn: Pair<Int, Boolean>): Frame {
    val frame = Frame.allocate(width, height)
    if (hasAlpha()) {
        // copyPixelsToBuffer hands the colours over multiplied by alpha, as the bitmap keeps them; getPixels does not.
        val row = IntArray(width)
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (color in row) {
                frame.pixels.put(Color.red(color).toByte()).put(Color.green(color).toByte())
                    .put(Color.blue(color).toByte()).put(Color.alpha(color).toByte())
            }
        }
        frame.pixels.rewind()
    } else {
        copyPixelsToBuffer(frame.pixels) // ARGB_8888 is R, G, B, A in memory
    }
    frame.rotation = turn.first
    frame.mirrored = turn.second
    return frame
}
