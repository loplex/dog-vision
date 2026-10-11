package cz.loplex.dogvision.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The layout's check: the cap and what may pass it, a `\` that ends a paragraph, a block under a paragraph's line. */
class LayoutTest {
    private fun lines(kind: Kind, text: String) = layoutProblems(kind, text, 40).map { it.line }

    private val words = "a sentence that runs on past the forty columns"

    @Test
    fun theKindIsTheExtensionsOrTheNames() {
        assertEquals(Kind.MARKDOWN, kindOf("docs/using.md"))
        assertEquals(Kind.KOTLIN, kindOf("build.gradle.kts"))
        assertEquals(Kind.C_LIKE, kindOf("docs/modules/uses.dot"))
        assertEquals(Kind.HASH, kindOf("packaging/web/dog-vision-web"))
        assertEquals(Kind.HASH, kindOf("android/.gitignore"))
        assertEquals(Kind.XML, kindOf("packaging/windows/dog-vision.wxs"))
        assertEquals(Kind.TROFF, kindOf("packaging/man/dog-vision.1"))
        assertNull(kindOf("gradlew"))
        assertNull(kindOf("packaging/licenses/Apache-2.0.txt"))
        assertNull(kindOf("gradle/package-lock.json"))
        assertNull(kindOf("docs/modules/uses.svg"))
    }

    @Test
    fun aLineOfWordsPastTheCapFails() {
        assertEquals(listOf(1), lines(Kind.MARKDOWN, words))
        assertEquals(listOf(1), lines(Kind.KOTLIN, "// $words"))
        assertEquals(listOf(2), lines(Kind.KOTLIN, "/**\n * $words\n */"))
        assertEquals(listOf(1), lines(Kind.HASH, "# $words"))
        assertEquals(listOf(2), lines(Kind.XML, "<!--\n  $words\n-->"))
        assertEquals(listOf(1, 2), lines(Kind.TROFF, ".\\\" $words\n$words"))
        assertEquals(listOf(1), lines(Kind.C_LIKE, "/* $words */"))
    }

    @Test
    fun codeOutsideKotlinIsNotMeasured() {
        assertEquals(emptyList(), lines(Kind.HASH, "echo $words"))
        assertEquals(emptyList(), lines(Kind.XML, "<string name=\"x\">$words</string>"))
        assertEquals(emptyList(), lines(Kind.C_LIKE, "var x = \"$words\";"))
        assertEquals(emptyList(), lines(Kind.TROFF, ".TH $words"))
        assertEquals(listOf(1), lines(Kind.KOTLIN, "val x = \"$words\""))
    }

    @Test
    fun markdownsCodeBlocksAndTablesAreNotMeasured() {
        assertEquals(emptyList(), lines(Kind.MARKDOWN, "```\n$words\n```\n\n| $words |"))
    }

    @Test
    fun aLinksTargetAndABareUrlAreNotCountedInMarkdown() {
        assertEquals(emptyList(), lines(Kind.MARKDOWN, "See [the model](docs/a-very-long-target-name-indeed.md)."))
        assertEquals(emptyList(), lines(Kind.MARKDOWN, "See https://example.com/a-very-long-target-name-indeed."))
    }

    @Test
    fun whatCannotBreakMayRunPastTheCapAlone() {
        val link = "[a label much longer than forty columns](x.md)"
        assertEquals(emptyList(), lines(Kind.MARKDOWN, "- $link."))
        assertEquals(emptyList(), lines(Kind.MARKDOWN, "  ![an image whose alt text runs past forty](x.png)"))
        assertEquals(emptyList(), lines(Kind.MARKDOWN, "`./gradlew :a-module:aVeryLongTaskName --and-option`,"))
        assertEquals(emptyList(), lines(Kind.KOTLIN, " * https://commons.wikimedia.org/wiki/File:Shiny_red_apples.jpg"))
        assertEquals(emptyList(), lines(Kind.HASH, "# <https://commons.wikimedia.org/wiki/File:Shiny_red_apples.jpg>"))
        assertEquals(emptyList(), lines(Kind.MARKDOWN, "[a label much longer than forty columns][reference]"))
        assertEquals(emptyList(), lines(Kind.MARKDOWN, "`./gradlew :a-module:aVeryLongTaskName --and-option` —"))
    }

    @Test
    fun whatCannotBreakBesideAWordDoesNotPass() {
        assertEquals(listOf(1), lines(Kind.MARKDOWN, "See [a label much longer than forty columns](x.md)"))
        assertEquals(listOf(1), lines(Kind.KOTLIN, " * See https://example.com/a-path-past-forty-columns"))
        assertEquals(listOf(1), lines(Kind.MARKDOWN, "`./gradlew` and `./gradlew :aVeryLongTaskName`"))
        assertEquals(listOf(1), lines(Kind.HASH, "# a_single_word_much_longer_than_forty_columns_in_all"))
        assertEquals(listOf(1), lines(Kind.MARKDOWN, "`./gradlew :a-module:aVeryLongTask` `--and-option`"))
    }

    @Test
    fun aBackslashThatEndsAParagraphFails() {
        assertEquals(listOf(1), lines(Kind.MARKDOWN, "One.\\\n\nTwo."))
        assertEquals(listOf(2), lines(Kind.MARKDOWN, "One.\\\nTwo.\\"))
        assertEquals(listOf(1), lines(Kind.MARKDOWN, "- One:\\\n  - two"))
        assertEquals(listOf(2), lines(Kind.MARKDOWN, "> One.\\\n> Two.\\\n\nThree."))
    }

    @Test
    fun aBackslashBetweenInformationsPasses() {
        assertEquals(emptyList(), lines(Kind.MARKDOWN, "One.\\\nTwo.\n\n- Three.\\\n  Four."))
        assertEquals(emptyList(), lines(Kind.MARKDOWN, "An escaped backslash: \\\\\n\n```\nls \\\n```"))
    }

    @Test
    fun aDocCommentsParagraphsAreMarkdown() {
        assertEquals(listOf(3), lines(Kind.KOTLIN, "/**\n * One.\\\n * Two.\\\n */\nfun f() = 1"))
        assertEquals(listOf(2), lines(Kind.KOTLIN, "/**\n * One.\\\n * @param x two\n */"))
        assertEquals(listOf(1), lines(Kind.KOTLIN, "/** One.\\ */"))
        assertEquals(emptyList(), lines(Kind.KOTLIN, "/**\n * One.\\\n * Two.\n *\n * @param x three.\n */"))
        assertEquals(emptyList(), lines(Kind.KOTLIN, "// One.\\\n// Two."))
        assertEquals(emptyList(), lines(Kind.C_LIKE, "/** One.\\ */"))
    }

    @Test
    fun aBlockUnderAParagraphsLineFails() {
        assertEquals(listOf(2), lines(Kind.MARKDOWN, "One\n# two"))
        assertEquals(listOf(2), lines(Kind.MARKDOWN, "One\n> two"))
        assertEquals(listOf(2), lines(Kind.MARKDOWN, "One\n<div>two</div>"))
        assertEquals(listOf(2), lines(Kind.MARKDOWN, "One\n1. two"))
        // CommonMark, and so GitHub, starts no list there that does not start at 1; IntelliJ IDEA does.
        assertEquals(listOf(2), lines(Kind.MARKDOWN, "One\n2. two"))
        assertEquals(listOf(2), lines(Kind.MARKDOWN, "One\n+ two"))
        assertEquals(listOf(2), lines(Kind.MARKDOWN, "One\n```\ntwo\n```"))
        assertEquals(listOf(2), lines(Kind.MARKDOWN, "One\n---"))
        assertEquals(listOf(3), lines(Kind.MARKDOWN, "- One\n  two\n  > three"))
        assertEquals(listOf(3), lines(Kind.KOTLIN, "/**\n * One\n * # two\n */"))
        assertEquals(listOf(2), lines(Kind.MARKDOWN, "- One\n# two"))
        assertEquals(listOf(2), lines(Kind.MARKDOWN, "> One\n1. two"))
    }

    @Test
    fun aBlockAfterABlankLineOrAListOfDashesPasses() {
        assertEquals(emptyList(), lines(Kind.MARKDOWN, "One\n\n# two\n\n> three\n\n1. four"))
        assertEquals(emptyList(), lines(Kind.MARKDOWN, "The lead-in:\n- one\n- two\n  - three"))
        val sample = "/**\n * For example:\n *\n * ```\n * @Composable\n * fun f() {}\n * ```\n */"
        assertEquals(emptyList(), lines(Kind.KOTLIN, sample))
    }
}
