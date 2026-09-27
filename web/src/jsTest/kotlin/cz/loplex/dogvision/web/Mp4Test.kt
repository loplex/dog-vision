package cz.loplex.dogvision.web

import org.w3c.files.Blob
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

/** The rotation of an MP4's video, read from its video track's header as a phone writes it. */
class Mp4Test {
    @Test
    fun readsEachQuarterTurnOfTheVideoTrack() {
        for ((rotation, matrix) in MATRICES) {
            assertEquals(rotation, rotationOfMovie(movie(sound(), video(matrix))), "the matrix $matrix")
        }
    }

    @Test
    fun readsAVersion1TrackHeader() {
        assertEquals(270, rotationOfMovie(movie(video(MATRICES.getValue(270), version = 1))))
    }

    @Test
    fun takesAMovieWithoutAVideoTrackOrWithAnotherMatrixAsUpright() {
        assertEquals(0, rotationOfMovie(movie(sound())))
        assertEquals(0, rotationOfMovie(movie(video(listOf(2, 0, 0, 2)))))
        assertEquals(0, rotationOfMovie(box("moov", box("trak", byteArrayOf(1, 2, 3)))))
    }

    @Test
    fun findsTheMovieAfterTheMediaAsAPhoneWritesIt(): Promise<Unit> =
        rotationOf(box("ftyp", "isom".encodeToByteArray()), box("mdat", ByteArray(5000)), movie(video(QUARTER)))
            .then { assertEquals(90, it) }

    @Test
    fun findsTheMovieAfterMediaOfA64BitSize(): Promise<Unit> {
        val media = ByteArray(16 + 3000)
        // A size of 1 says the real size follows the type, in 64 bits.
        byteArrayOf(0, 0, 0, 1, 'm'.code.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte())
            .copyInto(media)
        int(media.size).copyInto(media, 12)
        return rotationOf(media, movie(video(QUARTER))).then { assertEquals(90, it) }
    }

    @Test
    fun takesAFileThatIsNoMp4AsUpright(): Promise<Unit> =
        rotationOf(byteArrayOf(0x1A, 0x45, -33, -93, 0, 0, 0, 0, 0, 0, 0, 0)).then { assertEquals(0, it) }

    private fun rotationOf(vararg parts: ByteArray): Promise<Int> =
        Promise { resolve, _ -> readRotation(Blob(parts.map { it }.toTypedArray())) { resolve(it) } }

    private fun movie(vararg tracks: ByteArray) = box("moov", box("mvhd", ByteArray(100)), *tracks)

    private fun video(matrix: List<Int>, version: Int = 0) = track("vide", matrix, version)

    private fun sound() = track("soun", MATRICES.getValue(0), 0)

    /** A track of [handler]'s kind whose header holds [matrix]'s a, b, c and d. */
    private fun track(handler: String, matrix: List<Int>, version: Int): ByteArray {
        val times = if (version == 1) 32 else 20
        val header = ByteArray(4 + times + 16 + 36 + 8)
        header[0] = version.toByte()
        val (a, b, c, d) = matrix
        val at = 4 + times + 16
        int(a).copyInto(header, at)
        int(b).copyInto(header, at + 4)
        int(c).copyInto(header, at + 12)
        int(d).copyInto(header, at + 16)
        int(0x40000000).copyInto(header, at + 32) // w, 1.0 in 2.30
        val handlerBox = box("hdlr", ByteArray(8) + handler.encodeToByteArray() + ByteArray(12))
        return box("trak", box("tkhd", header), box("mdia", box("mdhd", ByteArray(24)), handlerBox))
    }

    private fun box(type: String, vararg content: ByteArray): ByteArray {
        val body = content.fold(ByteArray(0)) { all, part -> all + part }
        return int(8 + body.size) + type.encodeToByteArray() + body
    }

    private fun int(value: Int) = ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }

    private companion object {
        const val ONE = 0x10000

        /** A phone's matrix for a video recorded held upright: a, b, c and d, in 16.16. */
        val QUARTER = listOf(0, ONE, -ONE, 0)

        val MATRICES = mapOf(
            0 to listOf(ONE, 0, 0, ONE),
            90 to QUARTER,
            180 to listOf(-ONE, 0, 0, -ONE),
            270 to listOf(0, -ONE, ONE, 0),
        )
    }
}
