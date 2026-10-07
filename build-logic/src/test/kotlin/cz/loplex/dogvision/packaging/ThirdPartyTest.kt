package cz.loplex.dogvision.packaging

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The licences a package lists: read from the POMs, the natives' table, and written in DEP-5 and as notices. */
class ThirdPartyTest {
    private val texts = File(System.getProperty("licenceTexts"))

    private fun pom(body: String): File = Files.createTempFile("third-party", ".pom").toFile().apply {
        deleteOnExit()
        writeText("""<?xml version="1.0"?><project xmlns="http://maven.apache.org/POM/4.0.0">$body</project>""")
    }

    private fun licence(name: String, url: String) =
        "<licenses><license><name>$name</name><url>$url</url></license></licenses>"

    @Test
    fun everySpellingOfApacheTheLibrariesUseIsApache() {
        val spellings = listOf(
            "The Apache Software License, Version 2.0" to "https://www.apache.org/licenses/LICENSE-2.0.txt",
            "The Apache Software License, Version 2.0" to "http://www.apache.org/licenses/LICENSE-2.0.txt",
            "Apache-2.0" to "https://www.apache.org/licenses/LICENSE-2.0.txt",
            "The Apache License, Version 2.0" to "https://www.apache.org/licenses/LICENSE-2.0",
            "The Apache License, Version 2.0" to "http://www.apache.org/licenses/LICENSE-2.0.txt",
        )
        for ((name, url) in spellings) assertEquals("Apache-2.0", spdxOf(name, url), "$name $url")
        assertEquals("BSD-3-Clause", spdxOf("BSD-3-Clause", "https://www.lwjgl.org/license"))
        assertEquals(
            "BSD-3-Clause",
            spdxOf("BSD 3-Clause License", "https://chromium.googlesource.com/angle/angle/+/main/LICENSE"),
        )
        assertNull(spdxOf("GNU Lesser General Public License", "https://www.gnu.org/licenses/lgpl-3.0.txt"))
    }

    @Test
    fun theHolderIsTheOrganizationElseTheDevelopersOrganizationElseTheirNames() {
        val apache = licence("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt")
        val organization = pom("$apache<organization><name>The Android Open Source Project</name></organization>")
        val developerOrganization = pom(
            "$apache<developers><developer><name>Kotlin Team</name>" +
                "<organization>JetBrains</organization></developer></developers>",
        )
        val developer = pom("$apache<developers><developer><name>Kevin Bourrillion</name></developer></developers>")
        assertEquals("The Android Open Source Project", pomPart("g", "a", "1", organization, listOf("a.jar")).holder)
        assertEquals("JetBrains", pomPart("g", "a", "1", developerOrganization, listOf("a.jar")).holder)
        assertEquals("Kevin Bourrillion", pomPart("g", "a", "1", developer, listOf("a.jar")).holder)
        assertFailsWith<IllegalStateException> { pomPart("g", "a", "1", pom(apache), listOf("a.jar")) }
    }

    @Test
    fun lwjglIsBsdWithItsOwnTextAndHolder() {
        val lwjgl = pom(
            licence("BSD-3-Clause", "https://www.lwjgl.org/license") +
                "<developers><developer><name>Ioannis Tsakpinis</name></developer></developers>",
        )
        val part = pomPart("org.lwjgl", "lwjgl", "3.4.3", lwjgl, listOf("lwjgl-3.4.3.jar"))
        assertEquals(
            ThirdPartyPart(
                "org.lwjgl:lwjgl",
                "3.4.3",
                "BSD-3-Clause",
                "Lightweight Java Game Library",
                "lwjgl.txt",
                listOf("lwjgl-3.4.3.jar"),
            ),
            part,
        )
    }

    @Test
    fun aLicenceTheBuildDoesNotKnowOrNoLicenceFailsNamingTheModule() {
        val lgpl =
            pom(
                licence(
                    "LGPL",
                    "https://www.gnu.org/licenses/lgpl-3.0.txt",
                ) + "<organization><name>X</name></organization>",
            )
        val unknown = assertFailsWith<IllegalStateException> { pomPart("g", "a", "1", lgpl, listOf("a.jar")) }
        assertTrue("g:a:1" in unknown.message.orEmpty(), unknown.message)
        val none =
            assertFailsWith<IllegalStateException> {
                pomPart("g", "b", "2", pom("<organization><name>X</name></organization>"), listOf("b.jar"))
            }
        assertTrue("g:b:2" in none.message.orEmpty(), none.message)
    }

    @Test
    fun everyTextTheTableNamesIsInPackagingLicenses() {
        val named = EMBEDDED_PARTS.flatMap { it.second }.map { it.text } + listOf(APACHE_TEXT, "lwjgl.txt")
        for (text in named.distinct()) assertTrue(File(texts, text).isFile, text)
    }

    private val skia =
        ThirdPartyPart("Skia", "m150", "BSD-3-Clause", "Google Inc.", "skia.txt", listOf("libskiko-linux-x64.so"))
    private val skiko =
        ThirdPartyPart(
            "org.jetbrains.skiko:skiko-awt-runtime-linux-x64",
            "0.150.1",
            "Apache-2.0",
            "JetBrains",
            APACHE_TEXT,
            listOf("skiko-awt-runtime-linux-x64-0.150.1.jar", "libskiko-linux-x64.so"),
        )
    private val stdlib =
        ThirdPartyPart(
            "org.jetbrains.kotlin:kotlin-stdlib",
            "2.4.20",
            "Apache-2.0",
            "JetBrains",
            APACHE_TEXT,
            listOf("kotlin-stdlib-2.4.20.jar"),
        )

    @Test
    fun theCopyrightIsDep5WithAParagraphForEachFileAndEachLicence() {
        val copyright =
            dep5Copyright("Dog Vision", "M <m@x>", "https://x", "M", "GPL-3+", listOf(skia, skiko, stdlib), texts)
        val paragraphs = copyright.trimEnd().split("\n\n")
        assertTrue(
            paragraphs.first().startsWith(
                "Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/\n",
            ),
        )
        assertTrue("Files: *\nCopyright: M\nLicense: GPL-3+" in paragraphs)
        val native = paragraphs.single { it.startsWith("Files: */libskiko-linux-x64.so\n") }
        assertTrue("Copyright: Google Inc.\n JetBrains\n" in native, native)
        assertTrue("License: BSD-3-Clause-skia and Apache-2.0\n" in native, native)
        // Each short name a Files paragraph uses has a License paragraph of its own.
        val used = paragraphs.filter { it.startsWith("Files:") }.flatMap { p ->
            checkNotNull(Regex("""(?m)^License: (.+)$""").find(p)) { p }.groupValues[1].split(" and ")
        }.toSet()
        val defined = paragraphs.filter {
            it.startsWith("License:")
        }.map { it.lines().first().removePrefix("License: ") }.toSet()
        assertEquals(used, defined)
        val bsd = paragraphs.single { it.startsWith("License: BSD-3-Clause-skia\n") }
        assertTrue(bsd.lines().drop(1).all { it.startsWith(" ") }, bsd)
        assertTrue(" ." in bsd.lines(), bsd)
        assertTrue(
            "/usr/share/common-licenses/Apache-2.0" in paragraphs.single {
                it.startsWith("License: Apache-2.0\n")
            },
        )
        assertTrue("/usr/share/common-licenses/GPL-3" in paragraphs.single { it.startsWith("License: GPL-3+\n") })
    }

    @Test
    fun theNoticesListEachPartThenEachLicencesFullTextOnce() {
        val notices = thirdPartyNotices("A deb", OWN_SPDX, listOf(skia, skiko, stdlib), texts, listOf("A note."))
        assertTrue(
            notices.startsWith("A deb holds, besides its own code under GPL-3.0-or-later (LICENSE), what follows.\n"),
        )
        assertTrue("Skia m150, BSD-3-Clause, Google Inc.; in libskiko-linux-x64.so\n" in notices, notices)
        assertTrue("\nA note.\n" in notices)
        // The Apache text once, though two parts are under it.
        assertEquals(1, Regex("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION").findAll(notices).count())
        assertTrue(
            "The licence of org.jetbrains.kotlin:kotlin-stdlib, org.jetbrains.skiko:skiko-awt-runtime-linux-x64\n" in
                notices,
            notices,
        )
    }

    @Test
    fun theSpdxExpressionNamesEachLicenceOnceAfterTheProjectsOwn() {
        val jpeg =
            ThirdPartyPart(
                "libjpeg-turbo",
                "3.1.0",
                "IJG AND BSD-3-Clause AND Zlib",
                "x",
                "libjpeg-turbo.txt",
                listOf("a.so"),
            )
        assertEquals(
            "GPL-3.0-or-later AND BSD-3-Clause AND Apache-2.0 AND IJG AND Zlib",
            spdxExpression(OWN_SPDX, listOf(skia, skiko, stdlib, jpeg)),
        )
    }
}
