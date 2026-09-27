package cz.loplex.dogvision.core

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/**
 * The model gives what the desktop dog-vision gives, to rounding: reference.tsv holds its values,
 * printed by tools/reference_values.py.
 */
class ReferenceTest {
    private class Reference(val kind: String, val species: Species, val scale: ChromaScale?, val values: DoubleArray)

    private val references: List<Reference> =
        javaClass.getResourceAsStream("/reference.tsv")!!.bufferedReader().readLines().map { line ->
            val fields = line.split("\t")
            Reference(
                kind = fields[0],
                species = Species.byId(fields[1]) ?: error("No species ${fields[1]}"),
                scale = if (fields[2] == "-") null else ChromaScale.valueOf(fields[2].uppercase()),
                values = fields.drop(3).map(String::toDouble).toDoubleArray(),
            )
        }

    private val sceneMean = doubleArrayOf(0.8, 0.3, 0.1)

    @TestFactory
    fun `every reference value is matched`(): List<DynamicTest> = references.map { reference ->
        val species = reference.species
        DynamicTest.dynamicTest("${reference.kind} ${species.id} ${reference.scale ?: ""}") {
            val actual = when (reference.kind) {
                "simulation" -> simulationMatrix(Params(species, chromaScale = reference.scale!!)).flatten()

                "adapted" -> simulationMatrix(
                    Params(species, chromaScale = reference.scale!!, adaptation = 0.5, strength = 0.75),
                    sceneMean,
                ).flatten()

                "rnl-gains" -> rnlGains(species)

                "neutral-point" -> doubleArrayOf(neutralPoint(species))

                else -> error("Unknown kind ${reference.kind}")
            }
            assertAllClose(reference.values, actual, rtol = 1e-9, atol = 1e-12, what = reference.kind)
        }
    }

    @TestFactory
    fun `every species has its reference values`(): List<DynamicTest> = Species.entries.map { species ->
        DynamicTest.dynamicTest(species.id) {
            val kinds = references.filter { it.species == species }.map { it.kind }.toSet()
            val dichromatOnly = if (species.peaks.size == 2) setOf("neutral-point") else setOf()
            val expected = setOf("simulation", "adapted", "rnl-gains") + dichromatOnly
            assertEquals(expected, kinds)
        }
    }
}
