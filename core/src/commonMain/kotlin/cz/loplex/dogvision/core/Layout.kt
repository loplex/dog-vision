package cz.loplex.dogvision.core

/** A rectangle in pixels, measured from the top left corner. */
data class Box(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/** Where a view's images go on a screen: each with its caption right under it. */
data class ScreenLayout(val arrangement: Arrangement, val images: List<Box>, val captions: List<Box>)

/**
 * Lays [count] images of [imageWidth] x [imageHeight] out on an area of [areaWidth] x [areaHeight],
 * each with a caption [captionHeight] tall under it and [gap] between neighbours: side by side or
 * one above another, whichever shows them larger, and centred. An image is scaled, never cropped.
 */
fun layOut(
    areaWidth: Int,
    areaHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    count: Int,
    captionHeight: Int,
    gap: Int,
): ScreenLayout {
    val rowScale = minOf(
        (areaWidth - gap * (count - 1)).toDouble() / (count * imageWidth),
        (areaHeight - captionHeight).toDouble() / imageHeight,
    )
    val columnScale = minOf(
        areaWidth.toDouble() / imageWidth,
        (areaHeight - count * captionHeight - gap * (count - 1)).toDouble() / (count * imageHeight),
    )
    val arrangement = if (rowScale >= columnScale) Arrangement.ROW else Arrangement.COLUMN
    val scale = maxOf(rowScale, columnScale, 0.0)
    val width = (imageWidth * scale).toInt()
    val height = (imageHeight * scale).toInt()
    val (groupWidth, groupHeight) = when (arrangement) {
        Arrangement.ROW -> count * width + gap * (count - 1) to height + captionHeight
        Arrangement.COLUMN -> width to count * (height + captionHeight) + gap * (count - 1)
    }
    val left = (areaWidth - groupWidth) / 2
    val top = (areaHeight - groupHeight) / 2
    val images = List(count) { i ->
        when (arrangement) {
            Arrangement.ROW -> Box(left + i * (width + gap), top, width, height)
            Arrangement.COLUMN -> Box(left, top + i * (height + captionHeight + gap), width, height)
        }
    }
    return ScreenLayout(arrangement, images, images.map { Box(it.left, it.bottom, it.width, captionHeight) })
}
