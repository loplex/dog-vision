package cz.loplex.dogvision.common

import cz.loplex.dogvision.core.Matrix
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.animalConeMatrix
import cz.loplex.dogvision.core.neutralPoint
import cz.loplex.dogvision.core.rnlChromaMatrix
import cz.loplex.dogvision.core.rnlForms
import cz.loplex.dogvision.core.simulationMatrix
import cz.loplex.dogvision.core.speciesFacts
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import cz.loplex.dogvision.texts.nameKey
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2

/**
 * What the command line's --info prints for [params], and the windows show, worded by [texts]:
 * the species' cone matrix, a dichromat's confusion direction,
 * the simulation matrix at full strength, and the checks they pass:
 * grey kept grey, the cones excited as by the input, a dichromat's neutral point,
 * and the "rnl" scale matching the animal's discrimination and, for a trichromat, keeping blue's hue.\
 * Then the species' facts, and the published values to compare with.
 */
fun info(params: Params, texts: Texts): String {
    val species = params.species
    val mAnimal = animalConeMatrix(species)
    val cones = mAnimal.rows
    val t = simulationMatrix(params.copy(strength = 1.0))
    val checks = buildList {
        add(Str.INFO_CHECK_GREY to "T @ (1,1,1) = ${vector(t * DoubleArray(3) { 1.0 })}")
        add(Str.INFO_CHECK_CONES to "max |M_animal @ T - M_animal| = ${number((mAnimal * t - mAnimal).maxAbs())}")
        if (cones == 2) {
            val nanometres = "%.0f".format(Locale.ROOT, neutralPoint(species))
            add(Str.INFO_CHECK_NEUTRAL_POINT to "$nanometres nm")
        }
        if (cones > 1) {
            val k = rnlChromaMatrix(species)
            val (animal, human) = rnlForms(species)
            val error = (k.transpose() * human * k - animal).maxAbs() / animal.maxAbs()
            add(Str.INFO_CHECK_RNL_JNDS to "max |K'BK - A| / |A| = ${number(error)}")
        }
        if (cones == 3) {
            // Blue-yellow in the chromatic coordinates (q_S - q_L, q_M - q_L), the direction rnlChromaMatrix keeps.
            val q = mAnimal * doubleArrayOf(-0.5, -0.5, 1.0)
            val blueYellow = doubleArrayOf(q[0] - q[2], q[1] - q[2])
            val mapped = rnlChromaMatrix(species) * blueYellow
            val angle = abs(atan2(mapped[1], mapped[0]) - atan2(blueYellow[1], blueYellow[0]))
            add(Str.INFO_CHECK_RNL_BLUE to "angle of K d to d = ${number(angle)}")
        }
    }.map { (key, value) -> texts.get(key) to value }
    val facts = speciesFacts(species, texts.facts).map {
        texts.get(it.label.nameKey) + ":" to it.value.joinToString(" ")
    }
    return buildList {
        add(texts.get(Str.INFO_SPECIES, texts.get(species.nameKey), species.peaks.joinToString(", ") { decimal(it) }))
        add("")
        add(texts.get(Str.INFO_CONE_MATRIX))
        add(matrix(mAnimal))
        if (cones == 2) {
            val n = cross(mAnimal.row(0), mAnimal.row(1))
            val largest = n.maxOf(::abs)
            add("")
            add(texts.get(Str.INFO_CONFUSION, vector(n.map { it / largest }.toDoubleArray())))
        }
        add("")
        add(texts.get(Str.INFO_SIMULATION_MATRIX))
        add(matrix(t))
        add("")
        add(texts.get(Str.INFO_CHECKS))
        add(columns(checks))
        add("")
        add(columns(facts))
        add("")
        add(texts.get(Str.INFO_REFERENCE))
    }.joinToString("\n")
}

/** [rows] in two columns, the second as far in as its longest first column needs. */
private fun columns(rows: List<Pair<String, String>>): String {
    val width = rows.maxOf { it.first.length } + 2
    return rows.joinToString("\n") { (left, right) -> "  ${left.padEnd(width)}$right" }
}

/** Each row of [matrix] on a line of its own, its columns aligned, four decimals each. */
private fun matrix(matrix: Matrix): String = (0 until matrix.rows).joinToString("\n") { row ->
    matrix.row(row).joinToString(" ", prefix = "  ") { "%8.4f".format(Locale.ROOT, it) }
}

private fun vector(values: DoubleArray): String = values.joinToString(" ", "[", "]") { "%.4f".format(Locale.ROOT, it) }

/** A check's result, which is a rounding error where it holds: in scientific notation, two digits. */
private fun number(value: Double): String = "%.1e".format(Locale.ROOT, value)

/** A cone peak, as the species' table gives it. */
private fun decimal(value: Double): String = if (value % 1.0 == 0.0) "%.0f".format(Locale.ROOT, value) else "$value"

private fun Matrix.maxAbs(): Double = (0 until rows).maxOf { row(it).maxOf(::abs) }

/** The cross product of [a] and [b], which spans the null space of a 2 x 3 matrix of the two as rows. */
private fun cross(a: DoubleArray, b: DoubleArray) =
    doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
