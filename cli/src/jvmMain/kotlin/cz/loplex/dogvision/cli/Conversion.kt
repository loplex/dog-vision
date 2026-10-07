package cz.loplex.dogvision.cli

import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.core.meanLinearRgb
import cz.loplex.dogvision.core.shownName
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.io.IOException
import java.io.PrintStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Where [file] converted to [view] goes,
 * named after it and the species [view] shows, with the [extension] of what it is written as:
 * photo.jpg as photo.cat.png, or photo.horse-vs-cat.png where it shows a horse beside a cat,
 * next to it or in [outputDir].
 */
fun convertedFile(file: File, view: View, outputDir: File?, extension: String = "png"): File {
    val name = file.name.substringBeforeLast('.').ifEmpty { file.name } + ".${shownName(view)}.$extension"
    return File(outputDir ?: file.parentFile, name)
}

/**
 * Converts the photo or the video [Arguments.file] at full size to the view the arguments ask for,
 * and reports on [out] what it wrote, or on [err] why it could not, worded by [texts];
 * a video's conversion shows on [err] how far it is where [err] is a [terminal].\
 * Returns the exit status.
 */
@Suppress("MagicNumber", "ReturnCount")
fun convertFile(arguments: Arguments, texts: Texts, out: PrintStream, err: PrintStream, terminal: Boolean): Int {
    val file = checkNotNull(arguments.file) { "No file to convert" }
    val photo = try {
        readPhoto(file)
    } catch (error: IOException) {
        err.println(texts.get(Str.PHOTO_UNREADABLE, file, error.message))
        return 1
    } ?: return convertVideo(arguments, texts, out, err, terminal)
    val view = arguments.view.conversionView
    val output = convertedFile(file, view, arguments.outputDir)
    val (width, height) = composedSize(view, photo.width, photo.height)
    // core writes into the image's own pixels, which ImageIO then encodes without a copy.
    val composed = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val pixels = (composed.raster.dataBuffer as DataBufferInt).data
    val share = compose(photo, view, Image(width, height, pixels), { meanLinearRgb(photo) })
    try {
        output.parentFile?.mkdirs()
        if (!ImageIO.write(composed, "png", output)) throw IOException("no PNG writer")
    } catch (error: IOException) {
        err.println(texts.get(Str.PHOTO_UNWRITABLE, output, error.message))
        return 1
    }
    if (share != null) {
        out.println(texts.get(Str.PIXELS_DIFFER, texts.get(Str.PERCENT, (share * 100).roundToInt())))
    }
    out.println(texts.get(Str.PHOTO_WRITTEN, output))
    return 0
}
