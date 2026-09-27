package cz.loplex.dogvision.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * EXIF defines an orientation by where the stored image's first row and first column are seen: 6, "right, top", has
 * the first row down the right side and the first column along the top. Turned as [exifTurn] says, the first row's
 * first and last pixels land in the corners that definition names.
 */
class OrientationTest {
    private enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

    /** The corner that the pixel at ([x], [y]) of a [width] x [height] image lands in, turned and then mirrored. */
    private fun turn(x: Int, y: Int, width: Int, height: Int, rotation: Int, mirrored: Boolean): Corner {
        val (turnedX, turnedY, turnedWidth) = when (rotation) {
            90 -> Triple(height - 1 - y, x, height)
            180 -> Triple(width - 1 - x, height - 1 - y, width)
            270 -> Triple(y, width - 1 - x, height)
            else -> Triple(x, y, width)
        }
        val shownX = if (mirrored) turnedWidth - 1 - turnedX else turnedX
        val right = shownX != 0
        val bottom = turnedY != 0
        return when {
            !right && !bottom -> Corner.TOP_LEFT
            right && !bottom -> Corner.TOP_RIGHT
            right -> Corner.BOTTOM_RIGHT
            else -> Corner.BOTTOM_LEFT
        }
    }

    @Test
    fun theFirstRowLandsWhereExifSaysItIsSeen() {
        // Orientation to the corners the first row's first and last pixels are seen in.
        val seen = mapOf(
            1 to (Corner.TOP_LEFT to Corner.TOP_RIGHT),
            2 to (Corner.TOP_RIGHT to Corner.TOP_LEFT),
            3 to (Corner.BOTTOM_RIGHT to Corner.BOTTOM_LEFT),
            4 to (Corner.BOTTOM_LEFT to Corner.BOTTOM_RIGHT),
            5 to (Corner.TOP_LEFT to Corner.BOTTOM_LEFT),
            6 to (Corner.TOP_RIGHT to Corner.BOTTOM_RIGHT),
            7 to (Corner.BOTTOM_RIGHT to Corner.TOP_RIGHT),
            8 to (Corner.BOTTOM_LEFT to Corner.TOP_LEFT),
        )
        val (width, height) = 3 to 2
        for ((orientation, corners) in seen) {
            val (rotation, mirrored) = exifTurn(orientation)
            val first = turn(0, 0, width, height, rotation, mirrored)
            val last = turn(width - 1, 0, width, height, rotation, mirrored)
            assertEquals(corners, first to last, "orientation $orientation")
        }
    }

    @Test
    fun anOrientationOutsideExifsIsUpright() {
        for (orientation in listOf(0, 9, -1)) assertEquals(0 to false, exifTurn(orientation), "$orientation")
    }
}
