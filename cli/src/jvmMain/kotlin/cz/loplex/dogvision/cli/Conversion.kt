package cz.loplex.dogvision.cli

import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.core.meanLinearRgb
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.io.IOException
import java.io.PrintStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Where [file] converted goes: photo.jpg as photo.dog.png, next to it or in [outputDir]. */
fun convertedFile(file: File, outputDir: File?): File {
    val name = file.name.substringBeforeLast('.').ifEmpty { file.name } + ".dog.png"
    return File(outputDir ?: file.parentFile, name)
}

/**
 * Converts the photo [Arguments.file] at full size to the view the arguments ask for, as the Python program does, and
 * reports on [out] what it wrote, or on [err] why it could not, worded by [texts]; returns the exit status.
 */
fun convertPhoto(arguments: Arguments, texts: Texts, out: PrintStream, err: PrintStream): Int {
    val file = checkNotNull(arguments.file) { "No file to convert" }
    val photo = try {
        readPhoto(file)
    } catch (error: IOException) {
        err.println(texts.get(Str.PHOTO_UNREADABLE, file, error.message))
        return 1
    }
    if (photo == null) {
        err.println(texts.get(Str.PHOTO_NOT_PHOTO, file))
        return 1
    }
    val output = convertedFile(file, arguments.outputDir)
    val view = arguments.conversionView
    val (width, height) = composedSize(view, photo.width, photo.height)
    // core writes into the image's own pixels, which ImageIO then encodes without a copy.
    val composed = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val pixels = (composed.raster.dataBuffer as DataBufferInt).data
    val share = compose(photo, view, Image(width, height, pixels), { meanLinearRgb(photo) })
    if (share != null) {
        out.println(texts.get(Str.PIXELS_DIFFER, texts.get(Str.PERCENT, (share * 100).roundToInt())))
    }
    try {
        output.parentFile?.mkdirs()
        if (!ImageIO.write(composed, "png", output)) throw IOException("no PNG writer")
    } catch (error: IOException) {
        err.println(texts.get(Str.PHOTO_UNWRITABLE, output, error.message))
        return 1
    }
    out.println(texts.get(Str.PHOTO_WRITTEN, output))
    return 0
}
