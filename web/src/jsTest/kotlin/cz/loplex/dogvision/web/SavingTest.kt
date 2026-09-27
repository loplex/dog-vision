package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.rgb
import kotlin.test.Test
import kotlin.test.assertEquals

/** A snapshot holds the images of the view as the Android app's stitch puts them together. */
class SavingTest {
    private val left = Image(2, 1, intArrayOf(rgb(200, 10, 255), rgb(0, 128, 64)))
    private val right = Image(2, 1, intArrayOf(rgb(1, 2, 3), rgb(250, 251, 252)))

    @Test
    fun putsImagesSideBySide() {
        val canvas = stitch(listOf(left, right), Arrangement.ROW)
        assertEquals(4 to 1, canvas.width to canvas.height)
        assertEquals(listOf(left.pixels[0], left.pixels[1], right.pixels[0], right.pixels[1]), canvas.pixels.toList())
    }

    @Test
    fun putsImagesOneAboveAnother() {
        val canvas = stitch(listOf(left, right), Arrangement.COLUMN)
        assertEquals(2 to 2, canvas.width to canvas.height)
        assertEquals((left.pixels + right.pixels).toList(), canvas.pixels.toList())
    }
}
