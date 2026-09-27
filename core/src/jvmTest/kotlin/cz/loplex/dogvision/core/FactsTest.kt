package cz.loplex.dogvision.core

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The desktop dog-vision's English wording, which its tests check the facts in. */
object EnglishTexts : FactTexts {
    override val decimalSeparator = '.'

    override fun colourVision(kind: ColourVision) = kind.name.lowercase()

    override fun coneTypes(kind: String, count: Int) =
        if (count == 1) "$kind, 1 cone type" else "$kind, $count cone types"

    override fun percent(value: String) = "$value%"

    override fun percentRange(low: String, high: String) = "$low%–$high%"

    override fun shareOfCones(share: String) = "$share of cones"

    override val assumed = "assumed"
    override val noColourAxis = "no colour axis"
    override val seesOnlyGrey = "(sees only grey)"

    override fun rnlOfFixed(gains: List<String>) = gains.joinToString(" and ") + " of fixed"

    override val notFoundMeasured = "not found measured;"
    override val leftSharp = "left sharp"

    override fun acuity(value: String) = "$value c/deg"

    override fun acuityAcross(value: String) = "$value c/deg side by side,"

    override fun acuityUp(value: String) = "$value one above another"

    override fun note(note: Note) = note.english
}

/** English with a decimal comma, to see where the separator goes. */
object CommaTexts : FactTexts by EnglishTexts {
    override val decimalSeparator = ','
}

class FactsTest {
    companion object {
        @JvmStatic
        fun citations() = listOf(
            "Odom et al. 1983" to listOf("Odom et al. 1983"),
            "Neitz, Geist & Jacobs 1989" to listOf("Neitz, Geist & Jacobs 1989"), // authors stay together
            "Jacobs, Birch & Blakeslee 1982, 1.8 to 3.8" to listOf("Jacobs, Birch & Blakeslee 1982,", "1.8 to 3.8"),
            "Travis 1988, Williams 1992" to listOf("Travis 1988,", "Williams 1992"),
            "Bowmaker et al. 1978, 1991" to listOf("Bowmaker et al. 1978,", "1991"),
        ).map { arrayOf(it.first, it.second) }
    }

    private fun facts(species: Species, texts: FactTexts = EnglishTexts) =
        speciesFacts(species, texts).associate { it.label to it.value }

    @Test
    fun `a share is given in whole percent`() {
        assertEquals("10%", percent(0.1, 0.1, EnglishTexts))
        assertEquals("10%", percent(0.101, 0.104, EnglishTexts))
    }

    @Test
    fun `a range is given when its ends differ in whole percent`() {
        assertEquals("8%–12%", percent(0.08, 0.12, EnglishTexts))
    }

    @ParameterizedTest
    @MethodSource("citations")
    fun `pieces are cut after a comma that follows a number`(text: String, expected: List<String>) {
        assertEquals(expected, pieces(text))
        assertEquals(text, pieces(text).joinToString(" "))
    }

    @Test
    fun `numbers are rounded as Python formats them`() {
        assertEquals("420.7", formatSignificant(420.7, 6, '.'))
        assertEquals("429", formatSignificant(429.0, 6, '.'))
        assertEquals("548.9", formatSignificant(HumanCones.L - 10, 6, '.'))
        assertEquals("12.8", formatSignificant(12.85, 3, '.')) // 12.85 is a little less in binary
        assertEquals("3.66", formatSignificant(60 / (2 * 8.2), 3, '.'))
        assertEquals("0.79", formatFixed(0.785, 2, '.'))
    }

    @Test
    fun `the dog's facts`() {
        val facts = facts(Species.DOG).toMutableMap()
        val rnlScale = facts.remove(FactLabel.RNL_SCALE)!!.single()
        assertEquals(
            mapOf(
                FactLabel.COLOUR_VISION to listOf("dichromat, 2 cone types"),
                FactLabel.CONE_PEAKS to listOf("S 429 nm,", "L 555 nm", "(Neitz, Geist & Jacobs 1989)"),
                FactLabel.S_CONES to listOf("10%–18% of cones", "(Mowat et al. 2008)"),
                FactLabel.NEUTRAL_POINT to listOf("479 nm", "(model)"),
                FactLabel.ACUITY to listOf("11.6 c/deg", "(Odom et al. 1983)"),
            ),
            facts,
        )
        assertTrue(Regex("""x\d\.\d\d of fixed""").matches(rnlScale), rnlScale)
    }

    @Test
    fun `a trichromat has an assumed L to M ratio and two RNL factors`() {
        val facts = facts(Species.MACAQUE)
        assertEquals(listOf("trichromat, 3 cone types"), facts[FactLabel.COLOUR_VISION])
        assertEquals(listOf("S 431 nm,", "M 536 nm,", "L 565 nm"), facts[FactLabel.CONE_PEAKS]!!.take(3))
        assertEquals(listOf("1 : 1", "(assumed)"), facts[FactLabel.L_TO_M])
        assertTrue(" and " in facts[FactLabel.RNL_SCALE]!!.single())
        assertTrue(FactLabel.NEUTRAL_POINT !in facts)
    }

    @Test
    fun `a monochromat has no colour axis`() {
        val facts = facts(Species.HARBOUR_SEAL)
        assertEquals(listOf("monochromat, 1 cone type"), facts[FactLabel.COLOUR_VISION])
        assertEquals(listOf("no colour axis", "(sees only grey)"), facts[FactLabel.RNL_SCALE])
        assertTrue(FactLabel.S_CONES !in facts)
    }

    @Test
    fun `an unmeasured S-cone share is marked assumed`() {
        assertEquals(listOf("10% of cones", "(assumed)"), facts(Species.GOAT)[FactLabel.S_CONES])
    }

    @Test
    fun `a note is translated and kept after its citation`() {
        assertEquals(listOf("(Sumita et al. 2013,", "11.7 to 14)"), acuityValue(Species.SHEEP, EnglishTexts).drop(1))
        assertEquals(
            listOf("(L shifted 10 nm,", "see text)"),
            facts(Species.PROTANOMALOUS)[FactLabel.CONE_PEAKS]!!.drop(3),
        )
    }

    @ParameterizedTest
    @EnumSource(Species::class)
    fun `every species has every fact it should have, none of them empty`(species: Species) {
        val labels = speciesFacts(species, EnglishTexts).map { it.label }
        val colourAxes = when (species.peaks.size) {
            1 -> listOf()
            2 -> listOf(FactLabel.S_CONES, FactLabel.NEUTRAL_POINT)
            else -> listOf(FactLabel.S_CONES, FactLabel.L_TO_M)
        }
        val expected = listOf(FactLabel.COLOUR_VISION, FactLabel.CONE_PEAKS) + colourAxes +
            listOf(FactLabel.RNL_SCALE, FactLabel.ACUITY)
        assertEquals(expected, labels)
        val facts = speciesFacts(species, EnglishTexts)
        assertTrue(facts.all { fact -> fact.value.isNotEmpty() && fact.value.all(String::isNotEmpty) })
    }

    @Test
    fun `numbers in facts take the language's decimal separator`() {
        assertEquals(listOf("11,6 c/deg", "(Odom et al. 1983)"), acuityValue(Species.DOG, CommaTexts))
        assertEquals("S 420,7 nm,", facts(Species.HUMAN, CommaTexts)[FactLabel.CONE_PEAKS]!!.first())
    }

    @Test
    fun `acuity that differs by direction is given both ways`() {
        assertEquals(
            listOf("2.6 c/deg side by side,", "1.6 one above another", "(Rehkämper et al. 2000)"),
            acuityValue(Species.COW, EnglishTexts),
        )
    }

    @Test
    fun `an unmeasured acuity is left sharp`() {
        assertEquals(listOf("not found measured;", "left sharp"), acuityValue(Species.GOAT, EnglishTexts))
    }

    private val englishLabels = mapOf(
        "Colour vision" to FactLabel.COLOUR_VISION,
        "Cone peaks" to FactLabel.CONE_PEAKS,
        "S cones" to FactLabel.S_CONES,
        "L : M cones" to FactLabel.L_TO_M,
        "Neutral point" to FactLabel.NEUTRAL_POINT,
        "RNL scale" to FactLabel.RNL_SCALE,
        "Acuity" to FactLabel.ACUITY,
    )

    /** The desktop dog-vision's facts, in English: facts.tsv, written by tools/reference_values.py. */
    @TestFactory
    fun `every species has the facts the desktop dog-vision gives`(): List<DynamicTest> {
        val rows = javaClass.getResourceAsStream("/facts.tsv")!!.bufferedReader().readLines().map { it.split("\t") }
        val expected = rows.groupBy({ it[0] }) { Fact(englishLabels.getValue(it[1]), it.drop(2)) }
        assertEquals(Species.entries.map { it.id }, expected.keys.toList())
        return Species.entries.map { species ->
            DynamicTest.dynamicTest(species.id) {
                assertEquals(expected[species.id], speciesFacts(species, EnglishTexts))
            }
        }
    }
}
