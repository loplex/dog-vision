package cz.loplex.dogvision.cli

import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.neutralPoint
import cz.loplex.dogvision.texts.Texts
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** --info prints the model, the checks it passes, and the species' facts. */
class InfoTest {
    private val english = Texts.of("en")

    private fun info(species: Species, scale: String = "fixed"): List<String> =
        info(parseArguments(listOf("--species", species.id, "--chroma-scale", scale)).view.params, english).lines()

    /** A check's line: its name, then what it found, two spaces or more after it. */
    private val checkLine = Regex("""^ {2}(\S+(?: \S+)*) {2,}(.+)$""")

    /** The checks' lines that [info] prints, each its name and what it found. */
    private fun checks(printed: List<String>): Map<String, String> =
        printed.dropWhile { it != "Checks:" }.drop(1).takeWhile { it.isNotEmpty() }.associate { line ->
            val (name, found) = checkNotNull(checkLine.matchEntire(line)) { line }.destructured
            name to found
        }

    /** The error a check found, the number its line ends with. */
    private fun error(found: String) = found.substringAfterLast("= ").toDouble()

    @Test
    fun everyCheckOfEverySpeciesHoldsToRounding() {
        for (species in Species.entries) {
            for (scale in listOf("fixed", "rnl")) {
                val checks = checks(info(species, scale))
                assertEquals("T @ (1,1,1) = [1.0000 1.0000 1.0000]", checks["grey preserved"], "$species $scale")
                checks["rnl matches JNDs"]?.let { assertTrue(error(it) < 1e-9, "$species $scale: $it") }
                checks["rnl keeps blue"]?.let { assertTrue(error(it) < 1e-9, "$species $scale: $it") }
            }
            // The rnl scale changes the chroma, and so the cones' excitation, on purpose; the fixed one keeps it.
            assertTrue(error(checks(info(species)).getValue("cone-invariant")) < 1e-9, "$species")
        }
    }

    @Test
    fun aDichromatHasAConfusionDirectionAndANeutralPoint() {
        val printed = info(Species.DOG)
        assertTrue(printed.any { it.startsWith("Confusion direction n (normalised): [-1.0000 ") }, "$printed")
        val nanometres = "%.0f".format(Locale.ROOT, neutralPoint(Species.DOG))
        assertEquals(
            listOf("grey preserved", "cone-invariant", "neutral point", "rnl matches JNDs"),
            checks(printed).keys.toList(),
        )
        assertEquals("$nanometres nm", checks(printed)["neutral point"])
    }

    @Test
    fun aTrichromatsRnlScaleKeepsBlueAndItHasNoNeutralPoint() {
        val printed = info(Species.HUMAN)
        assertEquals(
            listOf("grey preserved", "cone-invariant", "rnl matches JNDs", "rnl keeps blue"),
            checks(printed).keys.toList(),
        )
        assertFalse(printed.any { it.startsWith("Confusion direction") })
    }

    @Test
    fun aMonochromatHasNoChromaticCheck() {
        val printed = info(Species.BOTTLENOSE_DOLPHIN)
        assertEquals(listOf("grey preserved", "cone-invariant"), checks(printed).keys.toList())
        assertFalse(printed.any { it.startsWith("Confusion direction") })
    }

    @Test
    fun theCommandLinePrintsItForTheSpeciesAskedFor() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status = runCommandLine(listOf("--info", "--species", "cat"), english, PrintStream(out), PrintStream(err))
        assertEquals(0, status)
        assertEquals("cat, cone peaks 450, 550 nm", out.toString().lines().first())
        assertEquals("", err.toString())
    }
}
