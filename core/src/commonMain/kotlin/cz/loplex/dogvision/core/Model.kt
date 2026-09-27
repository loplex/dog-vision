/*
 * The colour-vision model, for one species at a time.
 *
 * After Brettel, Viénot & Mollon 1997:
 *
 * 1. Every cone is modelled with the Govardovskii et al. (2000) A1 visual-pigment template,
 *    parametrised only by its peak wavelength.
 * 2. The display is modelled as three Gaussian primaries (typical sRGB LCD), scaled so that
 *    RGB (1,1,1) excites the human cones like D65 daylight.
 * 3. M_animal (2x3 for a dichromat) maps linear RGB to the animal's S and L cone excitations. Its
 *    one-dimensional null space n is the direction along which colours differ only in ways the
 *    animal cannot see.
 * 4. Each pixel is moved along n into the plane R = G. That plane contains the grey axis (so
 *    neutral colours stay neutral) and the blue-yellow axis (the conventional rendering of a
 *    dichromat's single chromatic axis). A cone monochromat is mapped onto the grey axis instead,
 *    and a trichromat maps onto all of RGB: nothing merges, so without step 6 its image is
 *    unchanged.
 * 5. Optionally the cones adapt to the scene (von Kries gains taken from the image mean, "grey
 *    world"), and the result is blended with the input. Optionally the image is also blurred to the
 *    animal's visual acuity, as AcuityView does (Caves & Johnsen 2018), for a given field of view.
 * 6. The saturation of the animal's colour axes is either left as step 4 gives it ("fixed"), or
 *    scaled so that one step the animal can just discriminate is one step a human can ("rnl"), both
 *    judged by the receptor noise limited model of Vorobyev & Osorio (1998), with the same cone
 *    noise for animal and human.
 *
 * The whole transform collapses into one 3x3 matrix on linear RGB. Without adaptation its rank
 * equals the number of cone types, and the animal's cone excitation of every output pixel equals
 * that of the input.
 */
package cz.loplex.dogvision.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Degrees across the image; a common camera, an assumption for photos. */
const val DEFAULT_FIELD_OF_VIEW = 60.0

enum class ChromaScale { FIXED, RNL }

data class Params(
    val species: Species = Species.DOG,
    /** 0 = adapted to daylight, 1 = fully to the scene mean. */
    val adaptation: Double = 0.0,
    /** 0 = original image, 1 = full simulation. */
    val strength: Double = 1.0,
    val chromaScale: ChromaScale = ChromaScale.FIXED,
    /** Blur to the species' visual acuity. */
    val acuity: Boolean = false,
    /** Degrees the image spans horizontally. */
    val fieldOfView: Double = DEFAULT_FIELD_OF_VIEW,
)

/**
 * Output subspace per number of cone types. For three: all of RGB. For two: the plane R = G, whose
 * columns are the grey-yellow and blue directions. For one: the grey axis.
 */
fun outputBasis(coneTypes: Int): Matrix = when (coneTypes) {
    3 -> Matrix.identity(3)
    2 -> Matrix.of(doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0))
    1 -> Matrix.ones(3, 1)
    else -> throw IllegalArgumentException("No output basis for $coneTypes cone types")
}

/** Gaussian approximation of a typical sRGB LCD: (peak nm, sigma nm) of R, G and B. */
val DISPLAY_PRIMARIES = listOf(610.0 to 20.0, 540.0 to 18.0, 450.0 to 10.0)

val WAVELENGTHS = DoubleArray(401) { 380.0 + it }

/** Relative spectral sensitivity of an A1 (retinal) visual pigment. */
fun govardovskiiA1(lambdaMax: Double, wl: DoubleArray = WAVELENGTHS): DoubleArray {
    val a = 0.8795 + 0.0459 * exp(-(lambdaMax - 300.0).pow(2) / 11940.0)
    val betaPeak = 189.0 + 0.315 * lambdaMax
    val betaWidth = -40.5 + 0.195 * lambdaMax
    return DoubleArray(wl.size) { i ->
        val x = lambdaMax / wl[i]
        val alpha = 1.0 / (exp(69.7 * (a - x)) + exp(28.0 * (0.922 - x)) + exp(-14.9 * (1.104 - x)) + 0.674)
        val beta = 0.26 * exp(-((wl[i] - betaPeak) / betaWidth).pow(2))
        alpha + beta
    }
}

/** Black-body spectrum; at 6504 K a stand-in for D65 daylight. */
fun planck(temperature: Double, wl: DoubleArray = WAVELENGTHS): DoubleArray {
    val h = 6.626e-34
    val c = 2.998e8
    val k = 1.381e-23
    return DoubleArray(wl.size) { i ->
        val wlM = wl[i] * 1e-9
        1.0 / (wlM.pow(5) * (exp(h * c / (wlM * k * temperature)) - 1.0))
    }
}

private fun sensitivities(conePeaks: List<Double>): Matrix =
    Matrix.of(*conePeaks.map { govardovskiiA1(it) }.toTypedArray())

/** Cone excitations (rows) produced by each display primary (columns). */
fun coneMatrix(conePeaks: List<Double>, primaries: Matrix): Matrix = sensitivities(conePeaks) * primaries.transpose()

private val HUMAN_PEAKS = listOf(HumanCones.S, HumanCones.M, HumanCones.L)

private val displayPrimaries: Matrix by lazy {
    val raw = Matrix.of(
        *DISPLAY_PRIMARIES.map { (peak, sigma) ->
            DoubleArray(WAVELENGTHS.size) { exp(-0.5 * ((WAVELENGTHS[it] - peak) / sigma).pow(2)) }
        }.toTypedArray(),
    )
    val human = coneMatrix(HUMAN_PEAKS, raw)
    val humanD65 = sensitivities(HUMAN_PEAKS) * planck(6504.0)
    val scales = human.solve(Matrix(3, 1, humanD65))
    Matrix.build(raw.rows, raw.cols) { i, j -> raw[i, j] * scales[i, 0] }
}

/** Primary spectra (3 x wavelengths), white-balanced to D65 for a human. */
fun displayPrimaries(): Matrix = displayPrimaries

private val animalConeMatrices: Map<Species, Matrix> by lazy {
    Species.entries.associateWith { species ->
        val m = coneMatrix(species.peaks, displayPrimaries)
        Matrix.build(m.rows, m.cols) { i, j -> m[i, j] / m.row(i).sum() } // von Kries adaptation to daylight
    }
}

/** M_animal (cones x 3): cone excitations from linear RGB, white mapping to all ones. */
fun animalConeMatrix(species: Species): Matrix = animalConeMatrices.getValue(species)

/** Von Kries gains that move the scene mean towards neutral as adaptation goes 0 -> 1. */
fun greyWorldGains(mAnimal: Matrix, meanRgb: DoubleArray, adaptation: Double): DoubleArray {
    val meanCones = (mAnimal * meanRgb).map { it.coerceAtLeast(1e-6) }
    val mean = meanCones.average()
    return DoubleArray(meanCones.size) { (mean / meanCones[it]).pow(adaptation) }
}

/** [I | -1]: takes cone excitations q to the chromatic coordinates c = (q_i - q_L). */
private fun toChroma(coneTypes: Int): Matrix = Matrix.build(coneTypes - 1, coneTypes) { i, j ->
    when {
        j == coneTypes - 1 -> -1.0
        i == j -> 1.0
        else -> 0.0
    }
}

/**
 * Columns: output colours that raise one non-L cone's excitation by one, leaving the rest.
 *
 * One column for a dichromat (S), two for a trichromat (S, M), none for a monochromat.
 */
fun chromaDirections(mAnimal: Matrix): Matrix {
    val n = mAnimal.rows
    val basis = outputBasis(n)
    val units = Matrix.build(n, n - 1) { i, j -> if (i == j) 1.0 else 0.0 }
    return basis * (mAnimal * basis).solve(units)
}

/** The S-cone share the RNL scale uses, and whether it was measured. */
fun sConeFraction(species: Species): Pair<Double, Boolean> {
    val share = species.sCones ?: return ASSUMED_S_CONE_FRACTION to false
    return (share.low + share.high) / 2 to true
}

/** Relative abundance of each cone class, short to long, summing to one. */
fun coneShares(species: Species): DoubleArray {
    val (fraction, _) = sConeFraction(species)
    if (species.peaks.size == 2) return doubleArrayOf(fraction, 1 - fraction)
    return doubleArrayOf(
        fraction,
        (1 - fraction) / (1 + ASSUMED_L_TO_M),
        (1 - fraction) * ASSUMED_L_TO_M / (1 + ASSUMED_L_TO_M),
    )
}

/**
 * RNL discrimination of small cone contrasts around grey, as a quadratic form.
 *
 * For cone contrasts f the squared distance in JNDs is f' P' (P E P')^-1 P f (Vorobyev & Osorio
 * 1998), with P taking f to the differences f_i - f_L and E the squared noise of each cone,
 * e_i = w / sqrt(n_i / n_max). The Weber fraction w is left at 1: it scales animal and human alike,
 * so it cancels in [rnlChromaMatrix]. Given the cone matrix, the form is returned in the coordinates
 * it takes.
 */
fun rnlMetric(species: Species, coneMatrixRgb: Matrix): Matrix {
    val n = coneMatrixRgb.rows
    val shares = coneShares(species)
    val largest = shares.max()
    val noiseSquared = Matrix.diagonal(DoubleArray(n) { largest / shares[it] })
    val p = toChroma(n)
    return coneMatrixRgb.transpose() * p.transpose() * (p * noiseSquared * p.transpose()).inverse() * p * coneMatrixRgb
}

private val rnlChromaMatrices: Map<Species, Matrix> by lazy {
    Species.entries.associateWith(::computeRnlChromaMatrix)
}

/**
 * K: how the "rnl" scale remaps the chromatic coordinates c = (q_i - q_L) of the fixed output.
 *
 * The fixed output is x' = q_L w + U c, with w the white and U = [chromaDirections]. Both observers
 * judge a small c by the RNL model around grey: the animal in its own cone coordinates, c' A c, and
 * a human looking at the output, (U c)' H (U c). Replacing U c by U K c with K' B K = A (B = U' H U)
 * makes the two agree. For a dichromat that fixes K as the single factor sqrt(A / B); for a
 * trichromat it leaves a rotation free, spent on keeping the hue of the blue-yellow direction. For a
 * human K is the identity.
 */
fun rnlChromaMatrix(species: Species): Matrix = rnlChromaMatrices.getValue(species)

/** The animal's form A and the human's B, in the chromatic coordinates of the species' fixed output. */
fun rnlForms(species: Species): Pair<Matrix, Matrix> {
    val mAnimal = animalConeMatrix(species)
    val n = mAnimal.rows
    // U c changes the cones by (c, 0): L stays, so c' A c is the form's leading block.
    val full = rnlMetric(species, Matrix.identity(n))
    val animal = Matrix.build(n - 1, n - 1) { i, j -> full[i, j] }
    val u = chromaDirections(mAnimal)
    val human = u.transpose() * rnlMetric(Species.HUMAN, animalConeMatrix(Species.HUMAN)) * u
    return animal to human
}

private fun computeRnlChromaMatrix(species: Species): Matrix {
    val mAnimal = animalConeMatrix(species)
    val n = mAnimal.rows
    if (n == 1) return Matrix(0, 0) // a monochromat has no chromatic axis to scale
    val (animal, human) = rnlForms(species)
    if (n == 2) return Matrix(1, 1, doubleArrayOf(sqrt(animal[0, 0] / human[0, 0])))
    // Any K with K'BK = A is B^-1/2 Q A^1/2 for a rotation Q. Q is chosen so that K maps the
    // blue-yellow direction onto itself: blue and yellow keep their hue, as in the fixed scale, and
    // only their saturation changes.
    val blueYellow = toChroma(n) * (mAnimal * doubleArrayOf(-0.5, -0.5, 1.0))
    val a = animal.symmetricPower(0.5) * blueYellow
    val b = human.symmetricPower(0.5) * blueYellow
    val angle = atan2(b[1], b[0]) - atan2(a[1], a[0])
    val rotation = Matrix.of(doubleArrayOf(cos(angle), -sin(angle)), doubleArrayOf(sin(angle), cos(angle)))
    return human.symmetricPower(-0.5) * rotation * animal.symmetricPower(0.5)
}

/** The factors the "rnl" scale applies along its principal axes, largest first. */
fun rnlGains(species: Species): DoubleArray {
    val k = rnlChromaMatrix(species)
    val eigenvalues = when (k.rows) {
        0 -> doubleArrayOf()

        1 -> doubleArrayOf(k[0, 0])

        else -> { // the real parts of the eigenvalues of a 2x2 matrix
            val halfTrace = (k[0, 0] + k[1, 1]) / 2
            val discriminant = halfTrace * halfTrace - (k[0, 0] * k[1, 1] - k[0, 1] * k[1, 0])
            if (discriminant >= 0) {
                doubleArrayOf(halfTrace + sqrt(discriminant), halfTrace - sqrt(discriminant))
            } else {
                doubleArrayOf(halfTrace, halfTrace)
            }
        }
    }
    return eigenvalues.sortedDescending().toDoubleArray()
}

/**
 * The 3x3 linear-RGB transform for the given parameters.
 *
 * Output colours lie in the subspace spanned by B = [outputBasis] and produce the (adapted) cone
 * excitation of the input: x' = B (M_animal B)^-1 diag(gains) M_animal x. With unit gains this is
 * the projection of x along the animal's confusion directions onto that subspace (the identity for a
 * trichromat). It equals x' = q_L w + U c with w the white, U = [chromaDirections] and
 * c = (q_i - q_L), and the "rnl" chroma scale replaces U c by U K c with K = [rnlChromaMatrix].
 * [meanRgb] is the scene's mean linear RGB, which adaptation needs.
 */
fun simulationMatrix(params: Params, meanRgb: DoubleArray? = null): Matrix {
    val mAnimal = animalConeMatrix(params.species)
    val n = mAnimal.rows
    val basis = outputBasis(n)
    val gains = if (meanRgb == null) DoubleArray(n) { 1.0 } else greyWorldGains(mAnimal, meanRgb, params.adaptation)
    val adapted = Matrix.diagonal(gains) * mAnimal
    val simulated = if (n > 1 && params.chromaScale == ChromaScale.RNL) {
        val chroma = chromaDirections(mAnimal) * rnlChromaMatrix(params.species) * toChroma(n) * adapted
        Matrix.outer(DoubleArray(3) { 1.0 }, adapted.row(n - 1)) + chroma
    } else {
        basis * (mAnimal * basis).solve(adapted)
    }
    return Matrix.identity(3) * (1.0 - params.strength) + simulated * params.strength
}

/** Wavelength of monochromatic light a dichromat sees with the same S/L ratio as white. */
fun neutralPoint(species: Species): Double {
    require(species.peaks.size == 2) { "Only a dichromat has a neutral point" }
    val sens = sensitivities(species.peaks)
    val white = coneMatrix(species.peaks, displayPrimaries) * DoubleArray(3) { 1.0 }
    val ratio = DoubleArray(WAVELENGTHS.size) { sens[0, it] / sens[1, it] - white[0] / white[1] }
    val index = (0 until WAVELENGTHS.size - 1).first { i ->
        WAVELENGTHS[i] > 420 && WAVELENGTHS[i] < 600 && sign(ratio[i]) != sign(ratio[i + 1])
    }
    return WAVELENGTHS[index]
}
