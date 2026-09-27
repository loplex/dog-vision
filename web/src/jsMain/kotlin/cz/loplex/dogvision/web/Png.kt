package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.blue
import cz.loplex.dogvision.core.green
import cz.loplex.dogvision.core.red
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.fetch.Response
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.math.abs

/** The eight bytes every PNG file starts with. */
private val SIGNATURE = byteArrayOf(-119, 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

/** PNG's colour type for red, green and blue without alpha: truecolour. */
private const val TRUECOLOUR = 2

/** The five filter types of PNG's filter method 0, by their numbers. */
private const val NONE = 0
private const val SUB = 1
private const val UP = 2
private const val AVERAGE = 3
private const val PAETH = 4

/** The bytes a pixel takes in a row: red, green and blue. */
private const val BYTES_PER_PIXEL = 3

/**
 * Encodes [image], opaque, as a PNG without an alpha channel (truecolour, 8 bits a sample), as the Android app saves
 * its snapshot; a canvas' toBlob always writes one with alpha. Calls [onEncoded] with the file, or [onFailure] where
 * the browser has no CompressionStream or it fails.
 */
fun encodePng(image: Image, onEncoded: (Blob) -> Unit, onFailure: () -> Unit) =
    encodePng(image.width, image.height, filteredRows(image), onEncoded, onFailure)

/** Encodes as [encodePng] does an image [width] by [height] whose rows are [filteredRows] already. */
internal fun encodePng(
    width: Int,
    height: Int,
    filteredRows: ByteArray,
    onEncoded: (Blob) -> Unit,
    onFailure: () -> Unit,
) {
    val rows = Blob(arrayOf(filteredRows))
    val compressed = try {
        // Its "deflate" is the zlib format, which a PNG's image data is in.
        val compressor: dynamic = js("new CompressionStream('deflate')")
        Response(rows.asDynamic().stream().pipeThrough(compressor)).arrayBuffer()
    } catch (error: Throwable) {
        onFailure()
        return
    }
    compressed.then({ buffer: ArrayBuffer ->
        val header = ByteArray(13)
        putInt(header, 0, width)
        putInt(header, 4, height)
        header[8] = 8 // bits a sample; compression, filter and interlace methods are 0
        header[9] = TRUECOLOUR.toByte()
        val data = Int8Array(buffer).unsafeCast<ByteArray>()
        val parts = arrayOf(SIGNATURE, chunk("IHDR", header), chunk("IDAT", data), chunk("IEND", ByteArray(0)))
        onEncoded(Blob(parts, BlobPropertyBag(type = "image/png")))
    }, { onFailure() })
}

/**
 * [image]'s rows as a PNG's image data holds them before compression: each its filter type, then its bytes filtered.
 *
 * Each row takes the filter that leaves the smallest sum of its bytes read as signed, the PNG specification's advice
 * for truecolour (and libpng's choice), unless [filter] names one for every row.
 */
internal fun filteredRows(image: Image, filter: Int? = null): ByteArray {
    val stride = image.width * BYTES_PER_PIXEL
    val out = ByteArray((stride + 1) * image.height)
    var above = IntArray(stride)
    var row = IntArray(stride)
    val filtered = IntArray(stride)
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val pixel = image.pixels[y * image.width + x]
            row[x * 3] = red(pixel)
            row[x * 3 + 1] = green(pixel)
            row[x * 3 + 2] = blue(pixel)
        }
        val chosen = filter ?: (NONE..PAETH).minBy { type ->
            filterRow(type, row, above, filtered)
            filtered.sumOf { if (it < 128) it else 256 - it }
        }
        filterRow(chosen, row, above, filtered)
        val start = y * (stride + 1)
        out[start] = chosen.toByte()
        for (i in 0 until stride) out[start + 1 + i] = filtered[i].toByte()
        above = row.also { row = above }
    }
    return out
}

/** Writes into [into] the bytes of [row] under the filter [type], given the row [above] it (all 0 above the first). */
private fun filterRow(type: Int, row: IntArray, above: IntArray, into: IntArray) {
    for (i in row.indices) {
        val left = if (i >= BYTES_PER_PIXEL) row[i - BYTES_PER_PIXEL] else 0
        val up = above[i]
        val upLeft = if (i >= BYTES_PER_PIXEL) above[i - BYTES_PER_PIXEL] else 0
        val predicted = when (type) {
            NONE -> 0
            SUB -> left
            UP -> up
            AVERAGE -> (left + up) / 2
            else -> paeth(left, up, upLeft)
        }
        into[i] = (row[i] - predicted) and 0xFF
    }
}

/** Of [left], [up] and [upLeft], the one nearest to left + up - upLeft, ties going in that order. */
private fun paeth(left: Int, up: Int, upLeft: Int): Int {
    val estimate = left + up - upLeft
    val toLeft = abs(estimate - left)
    val toUp = abs(estimate - up)
    val toUpLeft = abs(estimate - upLeft)
    return when {
        toLeft <= toUp && toLeft <= toUpLeft -> left
        toUp <= toUpLeft -> up
        else -> upLeft
    }
}

/** A PNG chunk: [data]'s length, the [type], the data, and the CRC-32 of the type and the data. */
private fun chunk(type: String, data: ByteArray): ByteArray {
    val bytes = ByteArray(12 + data.size)
    putInt(bytes, 0, data.size)
    type.forEachIndexed { i, char -> bytes[4 + i] = char.code.toByte() }
    data.copyInto(bytes, 8)
    putInt(bytes, 8 + data.size, crc32(bytes, 4, 8 + data.size))
    return bytes
}

/** Writes [value] into [bytes] at [offset], the most significant byte first, as PNG orders every integer. */
private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
    for (i in 0 until 4) bytes[offset + i] = (value ushr (24 - 8 * i)).toByte()
}

/** CRC-32's remainders of each byte, for the reversed polynomial 0xEDB88320 that PNG uses, as zlib and gzip do. */
private val CRC_TABLE = IntArray(256) { n ->
    (0 until 8).fold(n) { c, _ -> if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
}

/** The CRC-32 of [bytes] from [from] to [to], exclusive. */
internal fun crc32(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
    var crc = -1
    for (i in from until to) crc = CRC_TABLE[(crc xor bytes[i].toInt()) and 0xFF] xor (crc ushr 8)
    return crc.inv()
}
