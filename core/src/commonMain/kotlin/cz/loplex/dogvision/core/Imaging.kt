/*
 * The model applied to 8-bit sRGB images: sRGB decoding, the acuity blur, the map of differences and
 * the composed view.
 *
 * Images are rows of packed 0xAARRGGBB pixels, the way android.graphics.Bitmap hands them over, read
 * from a [PixelSource] and written to a [PixelSink] a strip of rows at a time, so that a large photo
 * never has to be held as floats in full.
 */
package cz.loplex.dogvision.core

import kotlin.math.PI
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Rows of packed 0xAARRGGBB pixels to read. */
interface PixelSource {
    val width: Int
    val height: Int

    /** Reads row [y] into [into], which holds [width] pixels. */
    fun readRow(y: Int, into: IntArray)
}

/** Where rows of packed 0xAARRGGBB pixels go. */
fun interface PixelSink {
    /** Writes [length] pixels from [pixels], starting at [offset], to row [y] from column [x] on. */
    fun writeRow(y: Int, x: Int, pixels: IntArray, offset: Int, length: Int)
}

/** An image held in memory, row by row. */
class Image(override val width: Int, override val height: Int, val pixels: IntArray = IntArray(width * height)) :
    PixelSource,
    PixelSink {
    init {
        require(pixels.size == width * height) { "$width x $height needs ${width * height} pixels" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    override fun readRow(y: Int, into: IntArray) {
        pixels.copyInto(into, 0, y * width, (y + 1) * width)
    }

    override fun writeRow(y: Int, x: Int, pixels: IntArray, offset: Int, length: Int) {
        pixels.copyInto(this.pixels, y * width + x, offset, offset + length)
    }
}

fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

fun red(pixel: Int): Int = (pixel shr 16) and 0xFF

fun green(pixel: Int): Int = (pixel shr 8) and 0xFF

fun blue(pixel: Int): Int = pixel and 0xFF

/** The sRGB transfer function, via lookup tables; the forward table is exact for 8-bit input. */
object Srgb {
    val decode = FloatArray(256) {
        val v = it / 255.0
        (if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)).toFloat()
    }

    const val ENCODE_STEPS = 4096

    private val encodeTable = IntArray(ENCODE_STEPS) {
        val v = it / (ENCODE_STEPS - 1.0)
        (255.0 * (if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055)).roundToInt()
    }

    /**
     * The 8-bit sRGB value of a linear one, clipped to 0..1. Rounded, not truncated: truncating
     * darkens values near black, where the curve is steepest, so that even the identity would not
     * give the image back.
     */
    fun encode(linear: Float): Int = encodeTable[(linear.coerceIn(0f, 1f) * (ENCODE_STEPS - 1) + 0.5f).toInt()]
}

/**
 * Gaussian sigmas in pixels (x, y) matching the species' acuity, or null for no blur.
 *
 * AcuityView (Caves & Johnsen 2018) multiplies the spectrum by the modulation transfer function
 * exp(-3.56 (MRA f)^2), with f in cycles per degree and MRA = 1 / acuity the minimum resolvable
 * angle. That is a Gaussian of sigma = sqrt(3.56 / (2 pi^2)) MRA degrees, which is cheaper to apply
 * to video than a Fourier transform.
 */
fun acuityBlur(params: Params, width: Int): Pair<Double, Double>? {
    val acuity = params.species.acuity
    if (!params.acuity || acuity == null) return null
    val pixelsPerDegree = width / params.fieldOfView
    val degrees = sqrt(3.56 / (2 * PI * PI))
    return degrees / acuity.across * pixelsPerDegree to degrees / acuity.up * pixelsPerDegree
}

/**
 * The normalised Gaussian kernel OpenCV's GaussianBlur uses on a float image: 2 round(4 sigma) + 1
 * taps, give or take the rounding of an odd size.
 */
fun gaussianKernel(sigma: Double): FloatArray {
    val size = (sigma * 8 + 1).roundToInt() or 1
    val centre = (size - 1) / 2.0
    val weights = DoubleArray(size) { exp(-(it - centre).pow(2) / (2 * sigma * sigma)) }
    val sum = weights.sum()
    return FloatArray(size) { (weights[it] / sum).toFloat() }
}

/** The index a border of reflected pixels (OpenCV's BORDER_REFLECT_101, "gfedcb|abcdefgh|gfedcba") reads. */
fun reflect101(index: Int, size: Int): Int {
    if (size == 1) return 0
    var i = index
    while (i < 0 || i >= size) i = if (i < 0) -i else 2 * size - 2 - i
    return i
}

/** Rows rendered at a time; enough to amortise the blur's margins, few enough to stay small. */
private const val STRIP_ROWS = 64

/**
 * Applies a linear-RGB 3x3 matrix, and optionally a Gaussian blur in linear light like the optics it
 * stands for, to [source], writing the result to [sink] with its left edge at column [x].
 */
fun applyMatrix(
    source: PixelSource,
    t: Matrix,
    blur: Pair<Double, Double>?,
    sink: PixelSink,
    x: Int = 0,
    rows: IntRange = 0 until source.height,
) {
    val width = source.width
    val coefficients = FloatArray(9) { t[it / 3, it % 3].toFloat() }
    val kernelX = blur?.let { gaussianKernel(it.first) }
    val kernelY = blur?.let { gaussianKernel(it.second) }
    val marginX = (kernelX?.size ?: 1) / 2
    val marginY = (kernelY?.size ?: 1) / 2
    val row = IntArray(width)
    val out = IntArray(width)
    for (stripStart in rows.first..rows.last step STRIP_ROWS) {
        val stripEnd = minOf(stripStart + STRIP_ROWS, rows.last + 1)
        // Every input row the strip's blur reaches, decoded to linear light.
        val inputRows = (stripStart - marginY until stripEnd + marginY).map { y ->
            source.readRow(reflect101(y, source.height), row)
            Array(3) { channel ->
                val shift = 16 - 8 * channel
                FloatArray(width) { Srgb.decode[(row[it] shr shift) and 0xFF] }
            }
        }
        val channels = Array(3) { c ->
            // Mixing the channels first and blurring the mix is the same as the reverse: both are linear.
            val mixed = inputRows.map { (r, g, b) ->
                FloatArray(width) {
                    coefficients[3 * c] * r[it] + coefficients[3 * c + 1] * g[it] + coefficients[3 * c + 2] * b[it]
                }
            }
            if (kernelX == null || kernelY == null) {
                mixed
            } else {
                val across = mixed.map { line ->
                    FloatArray(width) { px ->
                        var sum = 0f
                        for (k in kernelX.indices) sum += kernelX[k] * line[reflect101(px + k - marginX, width)]
                        sum
                    }
                }
                List(stripEnd - stripStart) { i ->
                    FloatArray(width) { px ->
                        var sum = 0f
                        for (k in kernelY.indices) sum += kernelY[k] * across[i + k][px]
                        sum
                    }
                }
            }
        }
        for (y in stripStart until stripEnd) {
            val (r, g, b) = channels.map { it[y - stripStart] }
            for (px in 0 until width) out[px] = rgb(Srgb.encode(r[px]), Srgb.encode(g[px]), Srgb.encode(b[px]))
            sink.writeRow(y, x, out, 0, width)
        }
    }
}

/** CIELAB Delta E*ab of one just-noticeable difference (Mahy et al. 1994). */
const val HUMAN_JND_DELTA_E = 2.3

/** Delta E*ab at which the difference map is fully red. */
const val DIFFERENCE_FULL_RED = 10.0

/** CIELAB (D65) of an 8-bit sRGB pixel. */
fun lab(pixel: Int): FloatArray {
    val r = Srgb.decode[red(pixel)]
    val g = Srgb.decode[green(pixel)]
    val b = Srgb.decode[blue(pixel)]
    val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f
    val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
    val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f
    fun f(t: Float): Float = if (t > (6.0 / 29).pow(3)) cbrt(t) else (t / (3 * (6.0 / 29).pow(2)) + 4.0 / 29).toFloat()
    return floatArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
}

/** The grey OpenCV's cvtColor gives an 8-bit RGB pixel, in its fixed-point arithmetic. */
fun luma(pixel: Int): Int = (red(pixel) * 4899 + green(pixel) * 9617 + blue(pixel) * 1868 + 8192) shr 14

/**
 * Where two images look different to a human: the right image in dimmed grey, reddened where the
 * CIELAB difference exceeds one just-noticeable difference, fully so at [DIFFERENCE_FULL_RED].
 * Writes one row of it into [out], and returns how many of its pixels differ noticeably.
 */
fun differenceRow(left: IntArray, right: IntArray, out: IntArray): Int {
    var noticeable = 0
    for (x in right.indices) {
        val a = lab(left[x])
        val b = lab(right[x])
        val deltaE = sqrt((a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2) + (a[2] - b[2]).pow(2))
        val grey = luma(right[x]) * 0.6f
        val weight = if (deltaE > HUMAN_JND_DELTA_E) (deltaE / DIFFERENCE_FULL_RED.toFloat()).coerceIn(0.3f, 1f) else 0f
        if (deltaE > HUMAN_JND_DELTA_E) noticeable++
        fun mix(red: Int) = (grey * (1 - weight) + red * weight).toInt()
        out[x] = rgb(mix(255), mix(40), mix(40))
    }
    return noticeable
}

/** Mean linear RGB of an image, estimated from every 8th pixel of every 8th row. */
fun meanLinearRgb(width: Int, height: Int, pixelAt: (x: Int, y: Int) -> Int): DoubleArray {
    val sum = DoubleArray(3)
    var count = 0
    for (y in 0 until height step 8) {
        for (x in 0 until width step 8) {
            val pixel = pixelAt(x, y)
            sum[0] += Srgb.decode[red(pixel)]
            sum[1] += Srgb.decode[green(pixel)]
            sum[2] += Srgb.decode[blue(pixel)]
            count++
        }
    }
    return DoubleArray(3) { sum[it] / count }
}

fun meanLinearRgb(image: Image): DoubleArray = meanLinearRgb(image.width, image.height) { x, y -> image[x, y] }

/** The simulation's matrix and blur for an image [width] wide, adapting to its mean if the params ask for it. */
fun simulationOf(width: Int, params: Params, meanRgb: () -> DoubleArray): Pair<Matrix, Pair<Double, Double>?> =
    simulationMatrix(params, if (params.adaptation > 0) meanRgb() else null) to acuityBlur(params, width)

/** How the images of a view are put together: side by side, or one above another. */
enum class Arrangement { ROW, COLUMN }

/**
 * What a view shows: the simulation, after the original or another species when [sideBySide], then
 * the map of differences if [difference] asks for it.
 */
data class View(
    val params: Params = Params(),
    val sideBySide: Boolean = true,
    /** The species on the left instead of the original, if any. */
    val compare: Species? = null,
    val difference: Boolean = false,
    val arrangement: Arrangement = Arrangement.ROW,
) {
    /** How many images the view shows. */
    val images: Int get() = when {
        !sideBySide -> 1
        difference -> 3
        else -> 2
    }
}

/** The size of the composed view of an image of [width] x [height]. */
fun composedSize(view: View, width: Int, height: Int): Pair<Int, Int> =
    if (view.arrangement == Arrangement.ROW) width * view.images to height else width to height * view.images

/**
 * Renders [view] of [source] to [sink], and returns the share of pixels that differ noticeably if
 * the map of differences is shown. [meanRgb] is the source's mean linear RGB, asked for only if the
 * cones adapt to the scene; [onRows] is told how many of the source's rows are done.
 */
fun compose(
    source: PixelSource,
    view: View,
    sink: PixelSink,
    meanRgb: () -> DoubleArray,
    onRows: (done: Int) -> Unit = {},
): Double? {
    val width = source.width
    val height = source.height
    val mean by lazy(meanRgb)
    val right = simulationOf(width, view.params) { mean }
    val left = when {
        !view.sideBySide -> null
        view.compare == null -> null
        else -> simulationOf(width, view.params.copy(species = view.compare)) { mean }
    }

    // Where image i of the view goes: its column and the row its first row lands on.
    fun place(image: Int): Pair<Int, Int> =
        if (view.arrangement == Arrangement.ROW) width * image to 0 else 0 to height * image
    val shifted = { image: Int, target: PixelSink ->
        val (dx, dy) = place(image)
        PixelSink { y, x, pixels, offset, length -> target.writeRow(y + dy, x + dx, pixels, offset, length) }
    }
    if (!view.sideBySide) {
        applyMatrix(source, right.first, right.second, sink)
        onRows(height)
        return null
    }
    var noticeable = 0L
    val leftStrip = Image(width, STRIP_ROWS)
    val rightStrip = Image(width, STRIP_ROWS)
    val a = IntArray(width)
    val b = IntArray(width)
    val diff = IntArray(width)
    for (stripStart in 0 until height step STRIP_ROWS) {
        val rows = stripStart until minOf(stripStart + STRIP_ROWS, height)
        val toStrip = { strip: Image -> PixelSink { y, x, p, o, l -> strip.writeRow(y - stripStart, x, p, o, l) } }
        if (left == null) {
            for (y in rows) {
                source.readRow(y, a)
                leftStrip.writeRow(y - stripStart, 0, a, 0, width)
            }
        } else {
            applyMatrix(source, left.first, left.second, toStrip(leftStrip), rows = rows)
        }
        applyMatrix(source, right.first, right.second, toStrip(rightStrip), rows = rows)
        for (y in rows) {
            leftStrip.readRow(y - stripStart, a)
            rightStrip.readRow(y - stripStart, b)
            shifted(0, sink).writeRow(y, 0, a, 0, width)
            shifted(1, sink).writeRow(y, 0, b, 0, width)
            if (view.difference) {
                noticeable += differenceRow(a, b, diff)
                shifted(2, sink).writeRow(y, 0, diff, 0, width)
            }
        }
        onRows(rows.last + 1)
    }
    return if (view.difference) noticeable.toDouble() / (width.toLong() * height) else null
}
