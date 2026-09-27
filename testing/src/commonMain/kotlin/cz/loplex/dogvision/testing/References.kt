package cz.loplex.dogvision.testing

import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.blue
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.core.differenceRow
import cz.loplex.dogvision.core.green
import cz.loplex.dogvision.core.meanLinearRgb
import cz.loplex.dogvision.core.red
import cz.loplex.dogvision.core.rgb
import kotlin.math.abs

/**
 * The image drawn by the reference script's `pattern()`, at its size unless another is given: neighbouring pixels
 * differ in every channel, so that a pixel read from the wrong place, or a channel from the wrong one, shows.
 */
fun pattern(width: Int = 40, height: Int = 30) = Image(
    width,
    height,
    IntArray(width * height) {
        val x = it % width
        val y = it / width
        rgb((x * 37 + y * 11) % 256, (x * 13 + y * 71) % 256, (x * 101 + y * 29) % 256)
    },
)

/**
 * What core renders of [image] for [view], cut into its images from left to right, and the share of differing pixels it
 * counts, null for a view without the map of differences.
 */
fun coreImages(image: Image, view: View): Pair<List<Image>, Double?> {
    val row = view.copy(arrangement = Arrangement.ROW)
    val (width, height) = composedSize(row, image.width, image.height)
    val composed = Image(width, height)
    val share = compose(image, row, composed, { meanLinearRgb(image) })
    val images = List(view.images) { i ->
        Image(
            image.width,
            image.height,
            IntArray(image.width * image.height) {
                composed[i * image.width + it % image.width, it / image.width]
            },
        )
    }
    return images to share
}

/** The map of differences core draws from [left] and [right], and the share of its pixels that differ noticeably. */
fun coreMap(left: Image, right: Image): Pair<Image, Double> {
    val map = Image(right.width, right.height)
    val a = IntArray(right.width)
    val b = IntArray(right.width)
    val row = IntArray(right.width)
    var noticeable = 0
    for (y in 0 until right.height) {
        left.readRow(y, a)
        right.readRow(y, b)
        noticeable += differenceRow(a, b, row)
        map.writeRow(y, 0, row, 0, row.size)
    }
    return map to noticeable.toDouble() / (right.width * right.height)
}

/** The largest difference of red, green or blue between [expected] and [actual], which must be of one size. */
fun worstChannelDifference(expected: Image, actual: Image): Int {
    require(expected.width == actual.width && expected.height == actual.height) {
        "${actual.width} x ${actual.height} where ${expected.width} x ${expected.height} was expected"
    }
    var worst = 0
    for (i in expected.pixels.indices) {
        for (channel in listOf(::red, ::green, ::blue)) {
            worst = maxOf(worst, abs(channel(expected.pixels[i]) - channel(actual.pixels[i])))
        }
    }
    return worst
}
