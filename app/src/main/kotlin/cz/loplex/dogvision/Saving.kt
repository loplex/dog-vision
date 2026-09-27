package cz.loplex.dogvision

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.graphics.createBitmap
import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.ClockTime
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.PixelSink
import cz.loplex.dogvision.core.PixelSource
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime

/** The folder under Pictures and Movies that what the app saves goes to. */
const val APP_FOLDER = "Dog vision"

/** Where the gallery keeps what the app saves: snapshots and photos in Pictures, videos in Movies. */
enum class Gallery(val mimeType: String, val directory: String, val collection: Uri) {
    IMAGES("image/png", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
    VIDEOS("video/mp4", Environment.DIRECTORY_MOVIES, MediaStore.Video.Media.EXTERNAL_CONTENT_URI),
    ;

    /** The folder as the user finds it, such as Pictures/Dog vision. */
    val folder: String get() = "$directory/$APP_FOLDER"
}

/** Whether saving to the gallery needs the storage permission, as it does before Android 10. */
val savingNeedsPermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

/** The time on the local clock now, which names what is saved. */
fun now(): ClockTime = LocalDateTime.now().run { ClockTime(year, monthValue, dayOfMonth, hour, minute, second) }

/** The images of a view put together as it shows them, side by side or one above another. */
fun stitch(images: List<Image>, arrangement: Arrangement): Bitmap {
    val width = images.first().width
    val height = images.first().height
    val bitmap = if (arrangement == Arrangement.ROW) {
        createBitmap(width * images.size, height)
    } else {
        createBitmap(width, height * images.size)
    }
    images.forEachIndexed { i, image ->
        val (x, y) = if (arrangement == Arrangement.ROW) width * i to 0 else 0 to height * i
        bitmap.setPixels(image.pixels, 0, width, x, y, width, height)
    }
    return bitmap
}

/** A bitmap's rows, for core's pipeline to read. */
class BitmapSource(private val bitmap: Bitmap) : PixelSource {
    override val width = bitmap.width
    override val height = bitmap.height

    override fun readRow(y: Int, into: IntArray) = bitmap.getPixels(into, 0, width, 0, y, width, 1)
}

/** A bitmap for core's pipeline to write rows into. */
class BitmapSink(val bitmap: Bitmap) : PixelSink {
    override fun writeRow(y: Int, x: Int, pixels: IntArray, offset: Int, length: Int) =
        bitmap.setPixels(pixels, offset, length, x, y, length, 1)
}

/**
 * Saves what [write] writes, called [name], in [gallery]'s folder, where the gallery shows it once it
 * is whole; throws IOException if it cannot be written, and leaves nothing behind then.
 */
fun saveToGallery(context: Context, name: String, gallery: Gallery, write: (OutputStream) -> Unit) {
    if (!savingNeedsPermission) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, gallery.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, gallery.folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(gallery.collection, values) ?: throw IOException("The gallery refused $name")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw IOException("Cannot write $name")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (error: IOException) {
            resolver.delete(uri, null, null)
            throw error
        }
        return
    }
    // Deprecated since Android 10, and the only way to Pictures and Movies before it.
    val folder = File(Environment.getExternalStoragePublicDirectory(gallery.directory), APP_FOLDER)
    if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot make $folder")
    val file = File(folder, name)
    try {
        file.outputStream().use(write)
    } catch (error: IOException) {
        file.delete()
        throw error
    }
    MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(gallery.mimeType), null)
}

/** Saves [bitmap] as a PNG called [name] in Pictures/[APP_FOLDER]; throws IOException if it cannot be written. */
fun savePng(context: Context, bitmap: Bitmap, name: String) = saveToGallery(context, name, Gallery.IMAGES) {
    write(bitmap, it)
}

private fun write(bitmap: Bitmap, out: OutputStream) {
    bitmap.setHasAlpha(false) // the view is opaque, so the PNG needs no alpha channel
    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("Cannot encode the image as PNG")
}
