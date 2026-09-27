package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.core.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/** The longest side a photo, a video or a camera is shown at, as in the Android app and the web page. */
const val PREVIEW_LONGEST_SIDE = 1280

/** A frame to render: tightly packed RGBA of [width] x [height], the top row first, in a direct buffer. */
class Frame(val width: Int, val height: Int, val pixels: ByteBuffer) {
    init {
        require(pixels.isDirect && pixels.remaining() == width * height * 4) { "Not $width x $height of RGBA" }
    }
}

/** [image]'s pixels as a [Frame]. */
fun frameOf(image: Image): Frame {
    val pixels = ByteBuffer.allocateDirect(image.width * image.height * 4).order(ByteOrder.BIG_ENDIAN)
    // 0xAARRGGBB turned once to the left is 0xRRGGBBAA, whose bytes, big end first, are RGBA.
    for (pixel in image.pixels) pixels.putInt(Integer.rotateLeft(pixel, 8))
    return Frame(image.width, image.height, pixels.flip())
}

/** The size [width] x [height] scaled down to fit [longest] on its longest side, and kept if it fits already. */
fun fitted(width: Int, height: Int, longest: Int = PREVIEW_LONGEST_SIDE): Pair<Int, Int> {
    val scale = minOf(1.0, longest.toDouble() / max(width, height))
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

/**
 * [image] scaled down to [fitted]'s size, halving it bilinearly while it is twice that or more and scaling the rest
 * bilinearly, as the Android app decodes a photo at a power of two of its size and scales the rest.
 */
fun preview(image: Image, longest: Int = PREVIEW_LONGEST_SIDE): Image {
    val (width, height) = fitted(image.width, image.height, longest)
    if (width == image.width && height == image.height) return image
    var current = image
    while (current.width / 2 >= width && current.height / 2 >= height) {
        current = scaled(current, current.width / 2, current.height / 2)
    }
    return if (current.width == width && current.height == height) current else scaled(current, width, height)
}

private fun scaled(image: Image, width: Int, height: Int): Image {
    val source = buffered(image)
    val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = target.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    graphics.drawImage(source, 0, 0, width, height, null)
    graphics.dispose()
    val pixels = (target.raster.dataBuffer as DataBufferInt).data
    for (i in pixels.indices) pixels[i] = pixels[i] or (0xFF shl 24)
    return Image(width, height, pixels)
}

private fun buffered(image: Image): BufferedImage {
    val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
    image.pixels.copyInto((buffered.raster.dataBuffer as DataBufferInt).data)
    return buffered
}
