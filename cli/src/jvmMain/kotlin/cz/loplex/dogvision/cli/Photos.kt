package cz.loplex.dogvision.cli

import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.exifTurn
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * The photo in [file] as sRGB, turned as its EXIF orientation says, or null if ImageIO cannot read it as an image.
 * ImageIO converts a photo with a colour profile of its own to sRGB as it hands its pixels over.
 */
fun readPhoto(file: File): Image? {
    val bytes = file.readBytes()
    val decoded = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
    val width = decoded.width
    val height = decoded.height
    val pixels = decoded.getRGB(0, 0, width, height, null, 0, width)
    for (i in pixels.indices) pixels[i] = pixels[i] or OPAQUE
    val (rotation, mirrored) = exifTurn(exifOrientation(bytes))
    return turned(Image(width, height, pixels), rotation, mirrored)
}

private const val OPAQUE = 0xFF shl 24

/** [image] turned [rotation] degrees clockwise, a multiple of 90, then mirrored left to right if [mirrored]. */
fun turned(image: Image, rotation: Int, mirrored: Boolean): Image {
    if (rotation % 360 == 0 && !mirrored) return image
    val w = image.width
    val h = image.height
    val quarter = rotation % 180 != 0
    val width = if (quarter) h else w
    val height = if (quarter) w else h
    val turned = Image(width, height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val u = if (mirrored) width - 1 - x else x // where the pixel was before the mirroring
            val pixel = when ((rotation % 360 + 360) % 360) {
                90 -> image[y, h - 1 - u]
                180 -> image[w - 1 - u, h - 1 - y]
                270 -> image[w - 1 - y, u]
                else -> image[u, y]
            }
            turned.pixels[y * width + x] = pixel
        }
    }
    return turned
}
