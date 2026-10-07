package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.common.UsageException
import cz.loplex.dogvision.common.ViewOptions
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

/** A window's command line: its own options, its usage, and where the usage and a mistake go. */
class WindowArgumentsTest {
    @Test
    fun everyOptionIsRead() {
        val args = listOf("--species=cat", "--difference", "--camera", "2", "--gl=wgl", "a.png")
        val arguments = parseWindowArguments(args)
        val view = ViewOptions(Params(Species.CAT), difference = true)
        assertEquals(WindowArguments(File("a.png"), 2, view, WindowsGl.WGL), arguments)
        assertTrue(arguments.windowView.sideBySide)
    }

    @Test
    fun whatCannotBeReadIsAUsageError() {
        val wrong = listOf(
            listOf("--camera", "1.5"),
            listOf("--camera", "-1"),
            listOf("--gl", "vulkan"),
            listOf("--species", "unicorn"),
            listOf("--window", "a.png"),
            listOf("--output-dir", "out"),
            listOf("a.png", "b.png"),
        )
        for (args in wrong) assertFailsWith<UsageException>(args.toString()) { parseWindowArguments(args) }
    }

    @Test
    fun theUsageNamesEveryOptionOfItsOwnInEachLanguage() {
        val options = listOf(
            "--species", "--compare", "--difference", "--adaptation", "--strength", "--chroma-scale", "--acuity",
            "--fov", "--camera", "--gl", "--help",
        )
        for (language in Texts.LANGUAGES) {
            val usage = windowUsage(Texts.of(language), "dog-vision-swing")
            assertTrue(usage.startsWith(Texts.of(language).get(Str.WINDOW_USAGE_SYNOPSIS, "dog-vision-swing")))
            for (option in options) assertTrue(option in usage, "$language: $option")
            for (option in listOf("--window", "--output-dir")) assertFalse(option in usage, "$language: $option")
        }
    }

    /** What [runWindow] said, whether as a mistake, and its status, or 99 where it opened the window. */
    private fun run(vararg args: String): Triple<String, Boolean?, Int> {
        var said = ""
        var mistake: Boolean? = null
        val status = runWindow("dog-vision-swing", args.toList(), Texts.of("en"), { text, wrong ->
            said = text
            mistake = wrong
        }) { 99 }
        return Triple(said, mistake, status)
    }

    @Test
    fun theUsageAndAMistakeAreSaidAndTheRestOpensTheWindow() {
        assertEquals(Triple("", null, 99), run("a.png"))
        val (usage, helpIsMistake, help) = run("--help")
        assertEquals(false to 0, helpIsMistake to help)
        assertTrue(usage.startsWith("usage: dog-vision-swing [options] [file]"), usage)
        val (error, wrongIsMistake, wrong) = run("--gl", "vulkan")
        assertEquals(true to 2, wrongIsMistake to wrong)
        assertEquals(
            "usage: dog-vision-swing [options] [file]\ndog-vision-swing: error: --gl: vulkan is neither angle nor wgl",
            error,
        )
    }
}
