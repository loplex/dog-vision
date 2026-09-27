package cz.loplex.dogvision.core

import cz.loplex.dogvision.testing.coreMap
import cz.loplex.dogvision.testing.pattern
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** An image of one grey. */
fun solid(value: Int, width: Int = 128, height: Int = 96) =
    Image(width, height, IntArray(width * height) { rgb(value, value, value) })

fun render(source: Image, view: View): Pair<Image, Double?> {
    val (width, height) = composedSize(view, source.width, source.height)
    val out = Image(width, height)
    val share = compose(source, view, out, { meanLinearRgb(source) })
    return out to share
}

fun simulate(source: Image, params: Params): Image = render(source, View(params, sideBySide = false)).first

/** The part of an image from column x and row y on, width by height pixels. */
fun Image.crop(x: Int, y: Int, width: Int, height: Int) =
    Image(width, height, IntArray(width * height) { this[x + it % width, y + it / width] })

fun standardDeviation(image: Image): Double {
    val values = image.pixels.flatMap { listOf(red(it), green(it), blue(it)) }.map(Int::toDouble)
    val mean = values.average()
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
}

class ImagingTest {
    private val photo = Random(0).let { random ->
        Image(64, 48, IntArray(64 * 48) { random.nextInt() or (0xFF shl 24) })
    }

    @Test
    fun `the matrix is read in RGB order`() {
        val none = doubleArrayOf(0.0, 0.0, 0.0)
        val redToBlue = Matrix.of(none, none, doubleArrayOf(1.0, 0.0, 0.0))
        val out = Image(1, 1)
        applyMatrix(Image(1, 1, intArrayOf(rgb(255, 0, 0))), redToBlue, null, out)
        assertEquals(rgb(0, 0, 255), out[0, 0])
    }

    @Test
    fun `the identity gives every value back`() {
        val values = Image(16, 16, IntArray(256) { rgb(it, it, it) })
        val out = Image(16, 16)
        applyMatrix(values, Matrix.identity(3), null, out)
        assertContentEquals(values.pixels, out.pixels)
    }

    @Test
    fun `strength zero shows the original`() {
        assertContentEquals(photo.pixels, simulate(photo, Params(strength = 0.0)).pixels)
    }

    @Test
    fun `the matrix works in linear light`() {
        val out = Image(1, 1)
        applyMatrix(solid(255, 1, 1), Matrix.identity(3) * 0.5, null, out)
        assertEquals(rgb(188, 188, 188), out[0, 0]) // sRGB of linear 0.5 is 187.5, not 128
    }

    @Test
    fun `the result is clipped to the displayable range`() {
        val out = Image(1, 1)
        applyMatrix(solid(200, 1, 1), Matrix.identity(3) * 4.0, null, out)
        assertEquals(rgb(255, 255, 255), out[0, 0])
    }

    @Test
    fun `no acuity blur unless asked for`() {
        assertNull(acuityBlur(Params(), 640))
    }

    @Test
    fun `no acuity blur for a species not measured`() {
        val species = Species.entries.first { it.acuity == null }
        assertNull(acuityBlur(Params(species, acuity = true), 640))
    }

    @Test
    fun `the acuity blur grows with the pixels per degree`() {
        val acuity = Species.COW.acuity!!
        val (sigmaX, sigmaY) = acuityBlur(Params(Species.COW, acuity = true, fieldOfView = 60.0), 600)!!
        assertEquals(sqrt(3.56 / (2 * PI * PI)) / acuity.across * 10, sigmaX, 1e-12)
        assertEquals(acuity.across / acuity.up, sigmaY / sigmaX, 1e-12) // cattle resolve less one above another
        val wider = acuityBlur(Params(Species.COW, acuity = true, fieldOfView = 30.0), 600)!!
        assertEquals(2 * sigmaX, wider.first, 1e-12)
    }

    @Test
    fun `the acuity blur removes detail`() {
        val sharp = simulate(photo, Params())
        val blurred = simulate(photo, Params(acuity = true, fieldOfView = 1.0)) // sigma of about 2 pixels
        assertTrue(standardDeviation(blurred) < standardDeviation(sharp))
    }

    @Test
    fun `a Gaussian kernel is normalised and as wide as OpenCV's`() {
        val kernel = gaussianKernel(2.0)
        assertEquals(17, kernel.size)
        assertEquals(1.0, kernel.sum().toDouble(), 1e-6)
        assertEquals(kernel.indices.maxBy { kernel[it] }, 8)
    }

    @Test
    fun `a border reflects without repeating the edge`() {
        assertEquals(listOf(2, 1, 0, 1, 2, 3, 2, 1), (-2..5).map { reflect101(it, 4) })
        assertEquals(0, reflect101(3, 1))
    }

    @Test
    fun `CIELAB of white, black and red`() {
        fun lab(r: Int, g: Int, b: Int) = lab(rgb(r, g, b)).map(Float::toDouble).toDoubleArray()
        assertAllClose(doubleArrayOf(100.0, 0.0, 0.0), lab(255, 255, 255), atol = 0.05)
        assertAllClose(doubleArrayOf(0.0, 0.0, 0.0), lab(0, 0, 0), atol = 1e-6)
        assertAllClose(doubleArrayOf(53.24, 80.09, 67.20), lab(255, 0, 0), atol = 0.05)
    }

    @Test
    fun `identical images do not differ`() {
        val (image, share) = coreMap(photo, photo)
        assertEquals(0.0, share)
        assertTrue(image.pixels.all { red(it) == blue(it) }) // grey, no red anywhere
    }

    @Test
    fun `a large difference is fully red`() {
        val (image, share) = coreMap(solid(0), solid(255))
        assertEquals(1.0, share)
        assertEquals(rgb(255, 40, 40), image[0, 0])
    }

    @Test
    fun `a difference below one JND is not marked`() {
        assertEquals(0.0, coreMap(solid(100), solid(101)).second)
    }

    @Test
    fun `the difference map reddens with the difference`() {
        val (slightImage, slight) = coreMap(solid(128), solid(135))
        val (moreImage, _) = coreMap(solid(128), solid(150))
        assertEquals(1.0, slight)
        assertTrue(red(slightImage[0, 0]) < red(moreImage[0, 0]))
    }

    @Test
    fun `mean linear RGB is in RGB order`() {
        assertAllClose(doubleArrayOf(1.0, 0.0, 0.0), meanLinearRgb(Image(1, 1, intArrayOf(rgb(255, 0, 0)))))
    }

    @Test
    fun `a dog sees grey as grey`() {
        val out = simulate(solid(128), Params(adaptation = 1.0, chromaScale = ChromaScale.RNL))
        assertTrue(out.pixels.all { it == rgb(128, 128, 128) })
    }

    @Test
    fun `compose shows the simulation alone`() {
        val (image, share) = render(photo, View(sideBySide = false))
        assertEquals(photo.width to photo.height, image.width to image.height)
        assertNull(share)
    }

    @Test
    fun `compose puts the original on the left`() {
        val (image, share) = render(photo, View())
        assertEquals(128 to 48, image.width to image.height)
        assertNull(share)
        assertContentEquals(photo.pixels, image.crop(0, 0, 64, 48).pixels)
        assertContentEquals(simulate(photo, Params()).pixels, image.crop(64, 0, 64, 48).pixels)
    }

    @Test
    fun `compose puts another species on the left`() {
        val (image, _) = render(photo, View(compare = Species.CAT))
        assertContentEquals(simulate(photo, Params(Species.CAT)).pixels, image.crop(0, 0, 64, 48).pixels)
    }

    @Test
    fun `compose adds the map of differences`() {
        val (image, share) = render(photo, View(difference = true))
        assertEquals(192 to 48, image.width to image.height)
        val (expected, expectedShare) = coreMap(photo, simulate(photo, Params()))
        assertContentEquals(expected.pixels, image.crop(128, 0, 64, 48).pixels)
        assertEquals(expectedShare, share)
        assertTrue(expectedShare > 0)
    }

    @Test
    fun `compose stacks the images in a column`() {
        val (image, _) = render(photo, View(difference = true, arrangement = Arrangement.COLUMN))
        val (row, _) = render(photo, View(difference = true))
        assertEquals(64 to 144, image.width to image.height)
        for (i in 0 until 3) {
            assertContentEquals(row.crop(64 * i, 0, 64, 48).pixels, image.crop(0, 48 * i, 64, 48).pixels)
        }
    }

    @Test
    fun `compose covers a source taller than a strip`() {
        val tall = Image(8, 150, IntArray(8 * 150) { rgb(it % 256, (it * 7) % 256, 90) })
        val (image, _) = render(tall, View(Params(acuity = true, fieldOfView = 1.0), difference = true))
        for (y in 0 until tall.height) {
            for (x in 0 until tall.width) {
                assertEquals(tall[x, y], image[x, y], "the original at $x, $y")
            }
        }
        val alone = simulate(tall, Params(acuity = true, fieldOfView = 1.0))
        assertContentEquals(alone.pixels, image.crop(8, 0, 8, 150).pixels)
    }

    @Test
    fun `cattle blur detail one above another more than side by side`() {
        val stripes = Image(64, 64, IntArray(64 * 64) { if ((it / 64) % 2 == 0) rgb(255, 255, 255) else rgb(0, 0, 0) })
        val turned = Image(64, 64, IntArray(64 * 64) { stripes[it / 64, it % 64] })
        val params = Params(Species.COW, acuity = true, fieldOfView = 30.0)
        assertTrue(standardDeviation(simulate(stripes, params)) < standardDeviation(simulate(turned, params)))
    }

    @Test
    fun `full adaptation makes a coloured scene grey`() {
        val scene = Image(16, 16, IntArray(256) { rgb(200, 120, 40) })
        val range = { pixel: Int -> listOf(red(pixel), green(pixel), blue(pixel)).let { it.max() - it.min() } }
        assertTrue(range(simulate(scene, Params())[0, 0]) > 20)
        assertTrue(range(simulate(scene, Params(adaptation = 1.0))[0, 0]) <= 1)
    }

    private val cases = mapOf(
        "dog" to View(sideBySide = false),
        "dog-rnl-adapted" to View(
            Params(adaptation = 0.5, strength = 0.75, chromaScale = ChromaScale.RNL),
            sideBySide = false,
        ),
        "dog-acuity" to View(Params(acuity = true, fieldOfView = 1.0), sideBySide = false),
        "cow-acuity" to View(Params(Species.COW, acuity = true, fieldOfView = 2.0), sideBySide = false),
        "seal" to View(Params(Species.HARBOUR_SEAL), sideBySide = false),
        "original-and-horse" to View(Params(Species.HORSE)),
        "deuteranope-dog-difference" to View(compare = Species.DEUTERANOPE, difference = true),
    )

    /**
     * The desktop dog-vision renders the same pattern alike, to one step of 8 bits where the two
     * round a float differently: images.tsv holds its results, written by tools/reference_values.py.
     */
    @TestFactory
    fun `every image the desktop dog-vision rendered is matched`(): List<DynamicTest> {
        val lines = javaClass.getResourceAsStream("/images.tsv")!!.bufferedReader().readLines()
        assertEquals(cases.keys, lines.map { it.substringBefore("\t") }.toSet())
        return lines.map { line ->
            val fields = line.split("\t")
            DynamicTest.dynamicTest(fields[0]) {
                val (image, share) = render(pattern(), cases.getValue(fields[0]))
                assertEquals(fields[1].toInt() to fields[2].toInt(), image.width to image.height)
                val values = fields.drop(4).map(String::toInt)
                val actual = image.pixels.flatMap { listOf(red(it), green(it), blue(it)) }
                val off = values.indices.filter { abs(values[it] - actual[it]) > 1 }
                if (off.isNotEmpty()) fail("${off.size} values differ by more than 1, first at ${off.first() / 3}")
                if (fields[3] == "None") {
                    assertNull(share)
                } else {
                    assertNotNull(share)
                    assertEquals(fields[3].toDouble(), share, 2.0 / (image.width * image.height / 3))
                }
            }
        }
    }
}
