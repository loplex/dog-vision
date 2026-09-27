package cz.loplex.dogvision.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelTest {
    companion object {
        @JvmStatic
        fun dichromats() = Species.entries.filter { it.peaks.size == 2 }

        @JvmStatic
        fun trichromats() = Species.entries.filter { it.peaks.size == 3 }

        @JvmStatic
        fun monochromats() = Species.entries.filter { it.peaks.size == 1 }

        @JvmStatic
        fun withColourAxes() = dichromats() + trichromats()

        @JvmStatic
        fun everyScale() = Species.entries.flatMap { species -> ChromaScale.entries.map { arrayOf(species, it) } }

        @JvmStatic
        fun dichromatsEveryScale() =
            dichromats().flatMap { species -> ChromaScale.entries.map { arrayOf(species, it) } }
    }

    private val ones = DoubleArray(3) { 1.0 }

    @Test
    fun `every kind of colour vision is among the species`() {
        assertTrue(dichromats().isNotEmpty() && trichromats().isNotEmpty() && monochromats().isNotEmpty())
    }

    @ParameterizedTest
    @ValueSource(doubles = [420.0, 500.0, 560.0])
    fun `a pigment is most sensitive at its peak`(peak: Double) {
        val sensitivity = govardovskiiA1(peak)
        val best = sensitivity.indices.maxBy { sensitivity[it] }
        assertEquals(peak, WAVELENGTHS[best], 1.0)
        assertEquals(1.0, sensitivity[best], 0.01)
    }

    @Test
    fun `the display white excites human cones like D65`() {
        val peaks = listOf(HumanCones.S, HumanCones.M, HumanCones.L)
        val human = coneMatrix(peaks, displayPrimaries())
        val d65 = Matrix.of(*peaks.map { govardovskiiA1(it) }.toTypedArray()) * planck(6504.0)
        assertAllClose(d65, human * ones, rtol = 1e-9)
    }

    @ParameterizedTest
    @EnumSource(Species::class)
    fun `the white excites each cone by one`(species: Species) {
        val mAnimal = animalConeMatrix(species)
        assertEquals(species.peaks.size to 3, mAnimal.rows to mAnimal.cols)
        assertAllClose(DoubleArray(mAnimal.rows) { 1.0 }, mAnimal * ones)
    }

    @ParameterizedTest
    @MethodSource("everyScale")
    fun `grey stays grey`(species: Species, scale: ChromaScale) {
        assertAllClose(ones, simulationMatrix(Params(species, chromaScale = scale)) * ones, atol = 1e-12)
    }

    @ParameterizedTest
    @EnumSource(Species::class)
    fun `the fixed scale excites the cones as the input does`(species: Species) {
        val mAnimal = animalConeMatrix(species)
        assertAllClose(mAnimal, mAnimal * simulationMatrix(Params(species)), atol = 1e-12)
    }

    @ParameterizedTest
    @MethodSource("everyScale")
    fun `the rank is the number of cone types`(species: Species, scale: ChromaScale) {
        assertEquals(species.peaks.size, simulationMatrix(Params(species, chromaScale = scale)).rank(1e-9))
    }

    @ParameterizedTest
    @MethodSource("dichromatsEveryScale")
    fun `a dichromat is shown in the plane red equals green`(species: Species, scale: ChromaScale) {
        val t = simulationMatrix(Params(species, chromaScale = scale))
        assertAllClose(t.row(0), t.row(1), atol = 1e-12)
    }

    @ParameterizedTest
    @MethodSource("monochromats")
    fun `a monochromat sees grey`(species: Species) {
        val t = simulationMatrix(Params(species))
        assertAllClose(Matrix.outer(ones, t.row(0)), t, atol = 1e-12)
    }

    @ParameterizedTest
    @EnumSource(ChromaScale::class)
    fun `a human sees the image unchanged`(scale: ChromaScale) {
        assertAllClose(Matrix.identity(3), simulationMatrix(Params(Species.HUMAN, chromaScale = scale)), atol = 1e-12)
    }

    @ParameterizedTest
    @EnumSource(Species::class)
    fun `strength blends with the original`(species: Species) {
        val full = simulationMatrix(Params(species))
        assertAllClose(Matrix.identity(3), simulationMatrix(Params(species, strength = 0.0)))
        assertAllClose(
            Matrix.identity(3) * 0.75 + full * 0.25,
            simulationMatrix(Params(species, strength = 0.25)),
            atol = 1e-15,
        )
    }

    @Test
    fun `no adaptation leaves the gains at one`() {
        val mAnimal = animalConeMatrix(Species.DOG)
        assertAllClose(doubleArrayOf(1.0, 1.0), greyWorldGains(mAnimal, doubleArrayOf(0.8, 0.3, 0.1), 0.0))
    }

    @Test
    fun `full adaptation makes the scene mean neutral`() {
        val mAnimal = animalConeMatrix(Species.DOG)
        val mean = doubleArrayOf(0.8, 0.3, 0.1)
        val cones = mAnimal * mean
        val gains = greyWorldGains(mAnimal, mean, 1.0)
        val adapted = DoubleArray(cones.size) { gains[it] * cones[it] }
        assertAllClose(DoubleArray(adapted.size) { adapted.average() }, adapted)
    }

    @Test
    fun `adaptation to a grey scene changes nothing`() {
        val params = Params(Species.DOG, adaptation = 1.0)
        assertAllClose(simulationMatrix(params), simulationMatrix(params, DoubleArray(3) { 0.2 }), atol = 1e-12)
    }

    @ParameterizedTest
    @MethodSource("withColourAxes")
    fun `a chroma direction raises one cone and leaves the rest`(species: Species) {
        val mAnimal = animalConeMatrix(species)
        val n = mAnimal.rows
        val units = Matrix.build(n, n - 1) { i, j -> if (i == j) 1.0 else 0.0 }
        assertAllClose(units, mAnimal * chromaDirections(mAnimal), atol = 1e-12)
    }

    @ParameterizedTest
    @MethodSource("withColourAxes")
    fun `cone shares sum to one`(species: Species) {
        val shares = coneShares(species)
        assertEquals(species.peaks.size, shares.size)
        assertEquals(1.0, shares.sum(), 1e-12)
    }

    @Test
    fun `a measured S-cone share is the middle of its range`() {
        val share = Species.DOG.sCones!!
        assertEquals((share.low + share.high) / 2 to true, sConeFraction(Species.DOG))
    }

    @Test
    fun `an unmeasured S-cone share is assumed`() {
        val species = dichromats().first { it.sCones == null }
        assertEquals(ASSUMED_S_CONE_FRACTION to false, sConeFraction(species))
    }

    @ParameterizedTest
    @MethodSource("withColourAxes")
    fun `the RNL scale makes a human count the animal's differences`(species: Species) {
        val k = rnlChromaMatrix(species)
        val (animal, human) = rnlForms(species)
        val largest = animal.flatten().maxOf { abs(it) }
        assertAllClose(animal, k.transpose() * human * k, rtol = 1e-9, atol = 1e-12 * largest)
    }

    @ParameterizedTest
    @MethodSource("trichromats")
    fun `the RNL scale keeps the hue of blue and yellow`(species: Species) {
        val k = rnlChromaMatrix(species)
        val cones = animalConeMatrix(species) * doubleArrayOf(-0.5, -0.5, 1.0)
        val blueYellow = doubleArrayOf(cones[0] - cones[2], cones[1] - cones[2])
        val mapped = k * blueYellow
        assertEquals(0.0, mapped[0] * blueYellow[1] - mapped[1] * blueYellow[0], 1e-12)
        assertTrue(mapped[0] * blueYellow[0] + mapped[1] * blueYellow[1] > 0)
    }

    @Test
    fun `the RNL scale is the identity for a human`() {
        assertAllClose(Matrix.identity(2), rnlChromaMatrix(Species.HUMAN), atol = 1e-12)
    }

    @Test
    fun `a monochromat has no RNL scale`() {
        val species = monochromats().first()
        assertEquals(0 to 0, rnlChromaMatrix(species).let { it.rows to it.cols })
        assertEquals(0, rnlGains(species).size)
    }

    @ParameterizedTest
    @MethodSource("withColourAxes")
    fun `RNL gains are largest first`(species: Species) {
        val gains = rnlGains(species)
        assertEquals(species.peaks.size - 1, gains.size)
        assertEquals(gains.sortedDescending(), gains.toList())
        assertTrue(gains.all { it > 0 })
    }

    @Test
    fun `the dog's neutral point is near the measured 480 nm`() {
        assertEquals(480.0, neutralPoint(Species.DOG), 5.0)
    }

    @ParameterizedTest
    @MethodSource("dichromats")
    fun `a dichromat's neutral point lies between its cone peaks`(species: Species) {
        val (short, long) = species.peaks
        assertTrue(neutralPoint(species) in short..long)
    }

    @ParameterizedTest
    @MethodSource("dichromats")
    fun `the RNL noise of a cone grows as its share falls`(species: Species) {
        // For two cones the form is 1 / (e_S^2 + e_L^2), with e_i^2 = n_max / n_i.
        val shares = coneShares(species)
        val noise = shares.map { shares.max() / it }
        val form = rnlMetric(species, Matrix.identity(2))
        assertEquals(1 / noise.sum(), form[0, 0], 1e-12)
        assertAllClose(Matrix.of(doubleArrayOf(1.0, -1.0), doubleArrayOf(-1.0, 1.0)) * form[0, 0], form, atol = 1e-15)
    }
}
