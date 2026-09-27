package cz.loplex.dogvision.cli

/**
 * The EXIF orientation a JPEG's APP1 segment gives, 1 to 8 as TIFF numbers them, or 1, upright, where [bytes] are no
 * JPEG, have no EXIF or give none. The JDK's ImageIO reads no EXIF, while OpenCV, which the Python program reads
 * photos with, turns them as it says; this is the one tag that needs.
 */
fun exifOrientation(bytes: ByteArray): Int {
    if (bytes.u8(0) != 0xFF || bytes.u8(1) != 0xD8) return 1
    var position = 2
    while (position + 4 <= bytes.size && bytes.u8(position) == 0xFF) {
        val marker = bytes.u8(position + 1)
        when {
            // a fill byte before the marker
            marker == 0xFF -> position++

            // markers without a segment
            marker == 0x01 || marker in 0xD0..0xD8 -> position += 2

            // the image data or its end, and no EXIF before it
            marker == 0xDA || marker == 0xD9 -> return 1

            else -> {
                val length = bytes.u16(position + 2, bigEndian = true)
                if (length < 2) return 1
                val start = position + 4
                if (marker == 0xE1 && EXIF_HEADER.indices.all { bytes.u8(start + it) == EXIF_HEADER[it].toInt() }) {
                    return tiffOrientation(bytes, start + EXIF_HEADER.size, position + 2 + length)
                }
                position += 2 + length
            }
        }
    }
    return 1
}

private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif" and two zero bytes

private const val ORIENTATION_TAG = 0x0112
private const val SHORT_TYPE = 3

/** The orientation tag of the first IFD of the TIFF structure in [bytes] from [start] to [end], or 1. */
private fun tiffOrientation(bytes: ByteArray, start: Int, end: Int): Int {
    if (end > bytes.size || start + 8 > end) return 1
    val bigEndian = when {
        // "MM"
        bytes.u8(start) == 0x4D && bytes.u8(start + 1) == 0x4D -> true

        // "II"
        bytes.u8(start) == 0x49 && bytes.u8(start + 1) == 0x49 -> false

        else -> return 1
    }
    if (bytes.u16(start + 2, bigEndian) != 42) return 1
    val directory = bytes.u32(start + 4, bigEndian)
    if (directory < 8 || start + directory + 2 > end) return 1
    val first = start + directory.toInt()
    val entries = bytes.u16(first, bigEndian)
    for (i in 0 until entries) {
        val entry = first + 2 + i * 12
        if (entry + 12 > end) return 1
        if (bytes.u16(entry, bigEndian) != ORIENTATION_TAG) continue
        if (bytes.u16(entry + 2, bigEndian) != SHORT_TYPE) return 1
        return bytes.u16(entry + 8, bigEndian).takeIf { it in 1..8 } ?: 1
    }
    return 1
}

private fun ByteArray.u8(at: Int): Int = if (at in indices) this[at].toInt() and 0xFF else -1

private fun ByteArray.u16(at: Int, bigEndian: Boolean): Int {
    val (high, low) = if (bigEndian) u8(at) to u8(at + 1) else u8(at + 1) to u8(at)
    return (high shl 8) or low
}

private fun ByteArray.u32(at: Int, bigEndian: Boolean): Long {
    val (high, low) = if (bigEndian) u16(at, true) to u16(at + 2, true) else u16(at + 2, false) to u16(at, false)
    return (high.toLong() shl 16) or low.toLong()
}
