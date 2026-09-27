package cz.loplex.dogvision.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutTest {
    @Test
    fun `a wide screen shows the images side by side`() {
        val layout = layOut(2000, 600, 640, 480, 2, captionHeight = 40, gap = 8)
        assertEquals(Arrangement.ROW, layout.arrangement)
        val (left, right) = layout.images
        assertEquals(left.top, right.top)
        assertEquals(left.right + 8, right.left)
    }

    @Test
    fun `a tall screen shows them one above another`() {
        val layout = layOut(1080, 2000, 640, 480, 3, captionHeight = 40, gap = 8)
        assertEquals(Arrangement.COLUMN, layout.arrangement)
        val (first, second) = layout.images
        assertEquals(first.left, second.left)
        assertEquals(first.bottom + 40 + 8, second.top)
    }

    @Test
    fun `the images keep their aspect and fit the area with their captions`() {
        for ((width, height) in listOf(2000 to 600, 1080 to 2000, 500 to 500)) {
            val layout = layOut(width, height, 640, 480, 3, captionHeight = 40, gap = 8)
            for (box in layout.images + layout.captions) {
                val inside = box.left >= 0 && box.top >= 0 && box.right <= width && box.bottom <= height
                assertTrue(inside, "$box in $width x $height")
            }
            for (box in layout.images) assertEquals(640.0 / 480, box.width.toDouble() / box.height, 0.01)
        }
    }

    @Test
    fun `each caption is right under its image, as wide`() {
        val layout = layOut(1080, 2000, 640, 480, 2, captionHeight = 40, gap = 8)
        for ((image, caption) in layout.images.zip(layout.captions)) {
            assertEquals(Box(image.left, image.bottom, image.width, 40), caption)
        }
    }

    @Test
    fun `the images are centred`() {
        val layout = layOut(1000, 1000, 100, 100, 1, captionHeight = 0, gap = 0)
        assertEquals(Box(0, 0, 1000, 1000), layout.images.single())
        val wide = layOut(1000, 500, 100, 100, 1, captionHeight = 0, gap = 0)
        assertEquals(Box(250, 0, 500, 500), wide.images.single())
    }
}
