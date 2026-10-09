package cz.loplex.dogvision.web.media

import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.rgb
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.files.Blob
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

/** A snapshot's PNG has no alpha channel, as the Android app's has none, and the browser reads it back as it was. */
class PngTest {
    /** A photo's variety in a few pixels: a smooth ramp, a flat patch and noise, so that more than one filter wins. */
    private val image = Image(23, 9).apply {
        var noise = 12345
        for (y in 0 until height) {
            for (x in 0 until width) {
                noise = noise * 1103515245 + 12345
                pixels[y * width + x] = when {
                    y < 3 -> rgb(x * 11, y * 40, 255 - x * 11)
                    y < 5 -> rgb(90, 90, 90)
                    else -> rgb((noise ushr 16) and 0xFF, (noise ushr 8) and 0xFF, (x * y) and 0xFF)
                }
            }
        }
    }

    @Test
    fun isTruecolourWithoutAlpha(): Promise<Unit> = encoded(filteredRows(image)).then { (bytes, _) ->
        assertEquals(listOf(137, 80, 78, 71, 13, 10, 26, 10), List(8) { bytes[it].toInt() and 0xFF })
        assertEquals("IHDR", CharArray(4) { (bytes[12 + it].toInt() and 0xFF).toChar() }.concatToString())
        assertEquals(8 to 2, (bytes[24].toInt() and 0xFF) to (bytes[25].toInt() and 0xFF), "bit depth and colour type")
    }

    @Test
    fun readsBackAsEncodedWithEachRowItsBestFilter(): Promise<Unit> = readsBack(filteredRows(image))

    @Test
    fun readsBackAsEncodedWithNoFilter(): Promise<Unit> = readsBack(filteredRows(image, 0))

    @Test
    fun readsBackAsEncodedWithSub(): Promise<Unit> = readsBack(filteredRows(image, 1))

    @Test
    fun readsBackAsEncodedWithUp(): Promise<Unit> = readsBack(filteredRows(image, 2))

    @Test
    fun readsBackAsEncodedWithAverage(): Promise<Unit> = readsBack(filteredRows(image, 3))

    @Test
    fun readsBackAsEncodedWithPaeth(): Promise<Unit> = readsBack(filteredRows(image, 4))

    @Test
    fun choosesMoreThanOneFilter() {
        val rows = filteredRows(image)
        val chosen = (0 until image.height).map { rows[it * (image.width * 3 + 1)].toInt() }.toSet()
        assertEquals(true, chosen.size > 1, "every row filtered by $chosen")
    }

    @Test
    fun countsCrc32AsZlibDoes() {
        // The check value of the CRC-32 that PNG, zlib and gzip use.
        assertEquals(0xCBF43926.toInt(), crc32("123456789".encodeToByteArray()))
    }

    /** Checks that the browser decodes [rows], encoded, to [image]'s pixels. */
    private fun readsBack(rows: ByteArray): Promise<Unit> = encoded(rows).then { (_, blob) -> blob }.then { blob ->
        Promise { resolve, reject ->
            val options = js("({ colorSpaceConversion: 'none', premultiplyAlpha: 'none' })")
            window.asDynamic().createImageBitmap(blob, options).then({ bitmap: dynamic ->
                try {
                    val canvas = document.createElement("canvas") as HTMLCanvasElement
                    canvas.width = image.width
                    canvas.height = image.height
                    val context = canvas.getContext("2d").unsafeCast<CanvasRenderingContext2D>()
                    context.drawImage(bitmap, 0.0, 0.0)
                    val data = context.getImageData(0.0, 0.0, image.width.toDouble(), image.height.toDouble()).data
                    fun byte(i: Int) = data[i].toInt() and 0xFF
                    val pixels = List(image.pixels.size) { rgb(byte(it * 4), byte(it * 4 + 1), byte(it * 4 + 2)) }
                    assertEquals(image.pixels.toList(), pixels)
                    resolve(Unit)
                } catch (error: Throwable) {
                    reject(error)
                }
            }, { error: Throwable -> reject(AssertionError("the browser cannot decode it: $error")) })
        }
    }.unsafeCast<Promise<Unit>>()

    /** [rows] encoded as a PNG of [image]'s size: the file's bytes and the file. */
    private fun encoded(rows: ByteArray): Promise<Pair<Uint8Array, Blob>> = Promise { resolve, reject ->
        encodePng(image.width, image.height, rows, { blob ->
            blob.asDynamic().arrayBuffer().then { buffer: ArrayBuffer -> resolve(Uint8Array(buffer) to blob) }
        }) { reject(AssertionError("the browser cannot compress")) }
    }
}
