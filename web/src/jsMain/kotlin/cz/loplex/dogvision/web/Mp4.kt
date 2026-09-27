package cz.loplex.dogvision.web

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.files.Blob

/** The top-level boxes read at most before a file is taken to have no rotation: an MP4 has a handful. */
private const val MAX_TOP_BOXES = 64

/** The largest movie box read: its sample tables grow with the length of the video, a few MB for hours. */
private const val MAX_MOVIE_BYTES = 64 * 1024 * 1024

/**
 * Reads the degrees clockwise the video of [file], an MP4 or a QuickTime movie, is to be turned to stand upright, as a
 * phone held upright records it: 0, 90, 180 or 270, from its video track's header, and calls [onRead] with them. A file
 * of another format, or one it cannot read, is taken to need no turn.
 *
 * Only the movie box is read, wherever it is in the file, and not the media before or after it.
 */
fun readRotation(file: Blob, onRead: (Int) -> Unit) {
    val fileSize = file.size.toDouble()
    fun at(offset: Double, boxes: Int) {
        if (boxes == MAX_TOP_BOXES || offset + 8 > fileSize) return onRead(0)
        read(file, offset, offset + 16, { header ->
            // A top-level box may be larger than an Int, as the media of a long video is.
            val declared = intAt(header, 0).toLong() and 0xFFFFFFFFL
            val size = when (declared) {
                0L -> fileSize - offset
                1L -> if (header.size < 16) 0.0 else longAt(header, 8).toDouble()
                else -> declared.toDouble()
            }
            when {
                size < 8 -> onRead(0)
                fourCc(header, 4) != "moov" -> at(offset + size, boxes + 1)
                size > MAX_MOVIE_BYTES -> onRead(0)
                else -> read(file, offset, offset + size, { movie -> onRead(rotationOfMovie(movie)) }) { onRead(0) }
            }
        }) { onRead(0) }
    }
    at(0.0, 0)
}

/** The rotation that the first video track in [movie], a whole movie box, states in its track header's matrix. */
internal fun rotationOfMovie(movie: ByteArray): Int {
    val tracks = children(movie, boxAt(movie, 0, movie.size) ?: return 0).filter { it.type == "trak" }
    val video = tracks.firstOrNull { track ->
        val media = children(movie, track).firstOrNull { it.type == "mdia" } ?: return@firstOrNull false
        val handler = children(movie, media).firstOrNull { it.type == "hdlr" } ?: return@firstOrNull false
        // After the version and flags, and a predefined 0, the handler type: "vide" for video.
        handler.content + 12 <= handler.end && fourCc(movie, handler.content + 8) == "vide"
    } ?: return 0
    val header = children(movie, video).firstOrNull { it.type == "tkhd" } ?: return 0
    // After the version and flags: the times, track ID and duration (32 bytes in version 1, 20 in 0), then 8 reserved
    // bytes, the layer, the alternate group, the volume and 2 reserved: 16 bytes, then the matrix a b u c d v x y w.
    val matrix = header.content + 4 + (if (movie[header.content].toInt() == 1) 32 else 20) + 16
    if (matrix + 20 > header.end) return 0
    val a = intAt(movie, matrix)
    val b = intAt(movie, matrix + 4)
    val c = intAt(movie, matrix + 12)
    val d = intAt(movie, matrix + 16)
    val one = 0x10000 // 1.0 in the matrix' 16.16 fixed point
    return when {
        a == 0 && b == one && c == -one && d == 0 -> 90
        a == -one && b == 0 && c == 0 && d == -one -> 180
        a == 0 && b == -one && c == one && d == 0 -> 270
        else -> 0
    }
}

/** A box: its [type], where it starts, where its content starts after the header, and where it ends. */
private class Mp4Box(val type: String, val start: Int, val content: Int, val end: Int)

/**
 * The box whose header is in [bytes] at [start], which ends by [end]; null if the header is cut short or states a size
 * that does not fit before [end]. A size of 0 runs to [end].
 */
private fun boxAt(bytes: ByteArray, start: Int, end: Int): Mp4Box? {
    if (start + 8 > end) return null
    val type = fourCc(bytes, start + 4)
    val declared = intAt(bytes, start).toLong() and 0xFFFFFFFFL
    val (size, header) = when (declared) {
        0L -> (end - start).toLong() to 8
        1L -> if (start + 16 > end) return null else longAt(bytes, start + 8) to 16
        else -> declared to 8
    }
    if (size < header || size > end - start) return null
    return Mp4Box(type, start, start + header, start + size.toInt())
}

/** The boxes inside [parent], as far as they fit in [bytes]. */
private fun children(bytes: ByteArray, parent: Mp4Box): List<Mp4Box> {
    val boxes = mutableListOf<Mp4Box>()
    var offset = parent.content
    val end = minOf(parent.end, bytes.size)
    while (true) {
        val box = boxAt(bytes, offset, end) ?: break
        boxes += box
        offset = box.end
    }
    return boxes
}

private fun fourCc(bytes: ByteArray, at: Int) = CharArray(4) {
    (bytes[at + it].toInt() and 0xFF).toChar()
}.concatToString()

private fun intAt(bytes: ByteArray, at: Int) =
    (0 until 4).fold(0) { value, i -> (value shl 8) or (bytes[at + i].toInt() and 0xFF) }

private fun longAt(bytes: ByteArray, at: Int) =
    (0 until 8).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 0xFF) }

/** Reads [file]'s bytes from [from] to [to], or as many as there are, and hands them to [onRead]. */
private fun read(file: Blob, from: Double, to: Double, onRead: (ByteArray) -> Unit, onFailure: () -> Unit) {
    val part = file.slice(from.asDynamic(), minOf(to, file.size.toDouble()).asDynamic())
    part.asDynamic().arrayBuffer().then(
        { buffer: ArrayBuffer -> onRead(Int8Array(buffer).unsafeCast<ByteArray>()) },
        { _: Throwable -> onFailure() },
    )
}
