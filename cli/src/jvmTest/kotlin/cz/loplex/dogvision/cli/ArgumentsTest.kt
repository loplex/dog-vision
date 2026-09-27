package cz.loplex.dogvision.cli

import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArgumentsTest {
    @Test
    fun noArgumentsAskForTheWindowWithTheDefaults() {
        val arguments = parseArguments(emptyList())
        assertEquals(Arguments(), arguments)
        assertFalse(arguments.converts)
    }

    @Test
    fun aFileAloneIsConverted() {
        val arguments = parseArguments(listOf("photo.jpg"))
        assertEquals(File("photo.jpg"), arguments.file)
        assertTrue(arguments.converts)
        assertFalse(parseArguments(listOf("--window", "photo.jpg")).converts)
    }

    @Test
    fun everyOptionIsRead() {
        val arguments = parseArguments(
            listOf(
                "--species", "cat", "--compare=dog", "--difference", "--adaptation", "0.25", "--strength=0.5",
                "--chroma-scale", "rnl", "--acuity", "--fov", "90", "--camera", "2", "--output-dir", "out", "--gl=wgl",
                "a.png",
            ),
        )
        val params = Params(Species.CAT, 0.25, 0.5, ChromaScale.RNL, acuity = true, fieldOfView = 90.0)
        val expected = Arguments(File("a.png"), false, 2, params, Species.DOG, true, File("out"), WindowsGl.WGL)
        assertEquals(expected, arguments)
    }

    @Test
    fun aConversionIsSideBySideOnlyWhenItComparesOrMapsDifferences() {
        assertFalse(parseArguments(listOf("a.png")).conversionView.sideBySide)
        assertTrue(parseArguments(listOf("--compare", "cat", "a.png")).conversionView.sideBySide)
        assertTrue(parseArguments(listOf("--difference", "a.png")).conversionView.sideBySide)
        assertTrue(parseArguments(listOf("a.png")).windowView.sideBySide)
    }

    @Test
    fun whatCannotBeReadIsAUsageError() {
        val wrong = listOf(
            listOf("--species", "unicorn"),
            listOf("--strength", "2"),
            listOf("--strength", "NaN"),
            listOf("--adaptation"),
            listOf("--chroma-scale", "vivid"),
            listOf("--fov", "0"),
            listOf("--camera", "1.5"),
            listOf("--camera", "-1"),
            listOf("--gl", "vulkan"),
            listOf("--acuity=yes"),
            listOf("--info"),
            listOf("a.png", "b.png"),
        )
        for (args in wrong) assertFailsWith<UsageException>(args.toString()) { parseArguments(args) }
    }

    @Test
    fun aMistakeIsWordedInEachLanguage() {
        val error = assertFailsWith<UsageException> { parseArguments(listOf("--fov", "0")) }
        assertEquals(Str.USAGE_NOT_ANGLE, error.key)
        assertEquals("--fov: 0 is not an angle", error.message(Texts.of("en")))
        assertEquals("--fov: 0 není úhel", error.message(Texts.of("cs")))
        assertEquals(error.message(Texts.of("en")), error.message)
    }

    @Test
    fun theUsageNamesEverySpeciesAndOptionInEachLanguage() {
        val options = listOf(
            "--species", "--compare", "--difference", "--adaptation", "--strength", "--chroma-scale", "--acuity",
            "--fov", "--window", "--camera", "--output-dir", "--gl", "--help",
        )
        for (language in Texts.LANGUAGES) {
            val usage = usage(Texts.of(language))
            for (species in Species.entries) assertTrue(" ${species.id}" in usage, "$language: ${species.id}")
            for (option in options) assertTrue(option in usage, "$language: $option")
        }
    }
}
