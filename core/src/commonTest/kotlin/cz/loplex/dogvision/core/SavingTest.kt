package cz.loplex.dogvision.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A snapshot is named after the species it shows and the time it was taken,
 * and holds the images of the view as the Android app's stitch puts them together.
 */
class SavingTest {
    private val time = ClockTime(2026, 9, 5, 7, 4, 3)
    private val left = Image(2, 1, intArrayOf(rgb(200, 10, 255), rgb(0, 128, 64)))
    private val right = Image(2, 1, intArrayOf(rgb(1, 2, 3), rgb(250, 251, 252)))

    @Test
    fun namesTheSpeciesAndTheTime() {
        assertEquals("dog-cat-20260905-070403.png", snapshotName(View(Params(species = Species.CAT)), time))
    }

    @Test
    fun namesTheSpeciesComparedWithFirst() {
        val view = View(Params(species = Species.CAT), compare = Species.HORSE)
        assertEquals("dog-horse-vs-cat-20260905-070403.png", snapshotName(view, time))
        assertEquals("dog-cat-20260905-070403.png", snapshotName(view.copy(sideBySide = false), time))
    }

    @Test
    fun putsTheSuffixBeforeTheExtension() {
        assertEquals(
            "dog-dog-20261231-235959-full.mp4",
            snapshotName(View(), ClockTime(2026, 12, 31, 23, 59, 59), suffix = "-full", extension = "mp4"),
        )
    }

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
