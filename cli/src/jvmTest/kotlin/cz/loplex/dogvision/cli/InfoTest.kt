package cz.loplex.dogvision.cli

import cz.loplex.dogvision.texts.Texts
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** --info prints what jvm-common's `info` words for the species asked for. */
class InfoTest {
    private val english = Texts.of("en")

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
