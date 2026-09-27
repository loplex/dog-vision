package cz.loplex.dogvision.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** A snapshot is named after the species it shows and the time it was taken, as the desktop window names it. */
class SavingTest {
    private val time = ClockTime(2026, 9, 5, 7, 4, 3)

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
}
