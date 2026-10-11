package cz.loplex.dogvision.layout

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

/** Where a file holds its prose: in Markdown, in comments of one of these shapes, or in troff's text lines. */
enum class Kind { MARKDOWN, KOTLIN, C_LIKE, HASH, XML, TROFF }

/**
 * The kind of a file by its path from the repository's root, or null for a file that holds no prose of the project's:
 * `gradlew`, which Gradle writes, has no extension listed, nor have the licences' texts and the test data,
 * nor the module graphs' SVG, which Graphviz draws.
 */
fun kindOf(path: String): Kind? {
    val name = path.substringAfterLast('/')
    return when {
        path == "packaging/web/dog-vision-web" -> Kind.HASH

        name in setOf(".editorconfig", ".gitignore", ".gitattributes") -> Kind.HASH

        else -> when (name.substringAfterLast('.', "")) {
            "md" -> Kind.MARKDOWN
            "kt", "kts" -> Kind.KOTLIN
            "java", "c", "h", "js", "ts", "css", "dot" -> Kind.C_LIKE
            "sh", "yml", "yaml", "toml", "py", "ps1", "properties" -> Kind.HASH
            "xml", "html", "wxs", "wxl" -> Kind.XML
            "1" -> Kind.TROFF
            else -> null
        }
    }
}

/** A line that breaks the layout, numbered from 1. */
data class Problem(val line: Int, val message: String)

/**
 * The lines of [text], a file of [kind], that break the layout docs/developing.md describes:
 * a line of prose longer than [limit] that could have been broken,
 * and in Markdown and KDoc a `\` that ends a paragraph, or a block that starts under a paragraph's line.
 */
fun layoutProblems(kind: Kind, text: String, limit: Int): List<Problem> {
    val lines = text.lines()
    val rendered = when (kind) {
        Kind.MARKDOWN -> markdownProblems(lines, 0)
        Kind.KOTLIN -> docComments(lines).flatMap { (first, comment) -> markdownProblems(comment, first) }
        else -> emptyList()
    }
    return (tooLong(kind, lines, limit) + rendered).sortedBy { it.line }
}

private val fence = Regex("""^\s*(```|~~~)""")
private val tableRow = Regex("""^\s*\|""")

// Markdown's lines are measured without a link's target or a bare URL, which no break can shorten.
private val target = Regex("""(?<=\]\()[^)]*(?=\))|https?://\S+""")

// What a line starts with that is no word of its prose: indentation, a comment's marker, a quote's, a list item's;
// and what it ends with: a comment's end, a hard break.
private val prefix = Regex("""^\s*(?:(?://+|/\*+|\*+|#+|<!--|\.\\"|>|[-+]|\d+[.)])\s*)*""")
private val suffix = Regex("""\s*\\?\s*(?:\*/|-->)?\s*$""")

// What cannot be broken: a link or an image with its label, inline or by reference, inline code, a URL.
private val unbreakable = Regex("""!?\[[^\]]*](?:\([^)]*\)|\[[^\]]*])?|(`+).+?\1|<https?://[^>]*>|https?://\S+""")
private val onlyOne = Regex("""[^\p{L}\p{N}\u0000]*\u0000[^\p{L}\p{N}\u0000]*""")

/** Whether a line's prose is one thing that cannot be broken, with nothing but punctuation around it. */
private fun cannotBreak(line: String): Boolean =
    onlyOne.matches(line.replace(prefix, "").replace(suffix, "").replace(unbreakable, "\u0000"))

private fun tooLong(kind: Kind, lines: List<String>, limit: Int): List<Problem> =
    proseLines(kind, lines).mapNotNull { i ->
        val line = lines[i]
        val counted = if (kind == Kind.MARKDOWN) line.replace(target, "") else line
        val width = counted.codePointCount(0, counted.length)
        when {
            width <= limit || cannotBreak(line) -> null
            kind == Kind.MARKDOWN -> Problem(i + 1, "$width > $limit characters but links' targets")
            else -> Problem(i + 1, "$width > $limit characters")
        }
    }

/**
 * The indices of the lines that are prose: Kotlin's every line, as ktlint leaves one that is a comment alone;
 * Markdown's but code blocks and tables;
 * elsewhere, the line comments, block comments and XML comments that start a line,
 * and troff's comments and text lines, which are not requests to it.
 */
private fun proseLines(kind: Kind, lines: List<String>): List<Int> {
    // Within a code block of Markdown, or within a comment that runs on to the next line.
    var open = false
    return lines.indices.filter { i ->
        val line = lines[i]
        val trimmed = line.trim()
        when (kind) {
            Kind.KOTLIN -> true

            Kind.MARKDOWN -> {
                val isFence = fence.containsMatchIn(line)
                if (isFence) open = !open
                !open && !isFence && !tableRow.containsMatchIn(line)
            }

            Kind.C_LIKE -> {
                val (inside, runsOn) = comment(open, trimmed, "/*", "*/")
                open = runsOn
                inside || trimmed.startsWith("//")
            }

            Kind.XML -> {
                val (inside, runsOn) = comment(open, trimmed, "<!--", "-->")
                open = runsOn
                inside
            }

            Kind.HASH -> trimmed.startsWith("#")

            Kind.TROFF -> trimmed.startsWith(".\\\"") || !(trimmed.startsWith(".") || trimmed.startsWith("'"))
        }
    }
}

/** Whether a line is in a comment that starts a line, and whether the comment runs on past it. */
private fun comment(open: Boolean, trimmed: String, start: String, end: String): Pair<Boolean, Boolean> {
    val starts = !open && trimmed.startsWith(start)
    val inside = open || starts
    return inside to (inside && end !in trimmed.substring(if (starts) start.length else 0))
}

private val docLine = Regex("""^\s*\*(?!/)\s?""")

/**
 * The KDoc of a Kotlin file, each comment as the index of its first line and the Markdown of its lines, one to a line:
 * a comment is taken to start where a line starts with its opening marker,
 * as no string of the repository's starts a line so.\
 * A comment's block tags, from `@param` on, are paragraphs of their own, so each is given apart;
 * an `@` in a code block is no tag.
 */
private fun docComments(lines: List<String>): List<Pair<Int, List<String>>> {
    val comments = mutableListOf<Pair<Int, List<String>>>()
    var i = 0
    while (i < lines.size) {
        if (!lines[i].trimStart().startsWith("/**")) {
            i++
            continue
        }
        val first = i
        val body = mutableListOf<String>()
        while (i < lines.size) {
            val text = if (i == first) {
                lines[i].trimStart().removePrefix("/**").removePrefix(" ")
            } else {
                lines[i].replace(docLine, "")
            }
            val end = text.indexOf("*/")
            body += if (end >= 0) text.substring(0, end) else text
            i++
            if (end >= 0) break
        }
        var start = 0
        var inCode = false
        for (j in 1..body.size) {
            if (j < body.size && fence.containsMatchIn(body[j])) inCode = !inCode
            if (j == body.size || (!inCode && body[j].trimStart().startsWith("@"))) {
                comments += first + start to body.subList(start, j)
                start = j
            }
        }
    }
    return comments
}

private val parser = MarkdownParser(GFMFlavourDescriptor(), true, CancellationToken.NonCancellable)

// The blocks that end a paragraph when they start on the line under it,
// which the repository's text writes after a blank line, so that one there is a line of prose that starts with markup.
// A list of `-` is no such block: a list follows its lead-in so.
// A table is none either, as the parser lets no table end a paragraph.
private val interrupting: Map<IElementType, String> = mapOf(
    MarkdownElementTypes.ATX_1 to "a heading",
    MarkdownElementTypes.ATX_2 to "a heading",
    MarkdownElementTypes.ATX_3 to "a heading",
    MarkdownElementTypes.ATX_4 to "a heading",
    MarkdownElementTypes.ATX_5 to "a heading",
    MarkdownElementTypes.ATX_6 to "a heading",
    MarkdownElementTypes.BLOCK_QUOTE to "a quote",
    MarkdownElementTypes.HTML_BLOCK to "an HTML block",
    MarkdownElementTypes.CODE_FENCE to "a code block",
    MarkdownElementTypes.ORDERED_LIST to "a numbered list",
    MarkdownElementTypes.UNORDERED_LIST to "a list of `+` or `*`",
    MarkdownTokenTypes.HORIZONTAL_RULE to "a rule",
)

/** The problems [lines] of Markdown have once rendered, numbered as the file's line [first] is the first of them. */
private fun markdownProblems(lines: List<String>, first: Int): List<Problem> {
    val markdown: CharSequence = lines.joinToString("\n")
    val starts = lines.runningFold(0) { start, line -> start + line.length + 1 }
    fun lineOf(offset: Int) = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 } + first + 1
    val problems = mutableListOf<Problem>()
    fun visit(node: ASTNode) {
        val text = markdown.substring(node.startOffset, node.endOffset)
        when (node.type) {
            MarkdownElementTypes.PARAGRAPH -> {
                val body = text.trimEnd()
                if (body.takeLastWhile { it == '\\' }.length % 2 == 1) {
                    problems += Problem(
                        lineOf(node.startOffset + body.length - 1),
                        "a `\\` ends the paragraph, where it renders as itself",
                    )
                }
            }

            MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 -> problems += Problem(
                lineOf(node.endOffset - 1),
                "a line of `=` or `-` makes the paragraph above it a heading",
            )
        }
        // The parser has a paragraph and a rule for tokens, which the other tokens, a line's end and a marker, are not.
        val blocks = node.children.filter { it.type in blockTokens || !isToken(it.type) }
        blocks.zipWithNext { above, block ->
            val name = interrupting[block.type]
            val dash = block.type == MarkdownElementTypes.UNORDERED_LIST &&
                markdown.substring(block.startOffset, block.endOffset).trimStart().startsWith("-")
            val paragraph = lastParagraph(above)
            if (paragraph != null && name != null && !dash &&
                lineOf(block.startOffset) == lineOf(paragraph.endOffset - 1) + 1
            ) {
                problems += Problem(lineOf(block.startOffset), "$name starts under a paragraph's line, and ends it")
            }
        }
        node.children.forEach(::visit)
    }
    visit(parser.buildMarkdownTreeFromString(markdown))
    return problems
}

/**
 * The paragraph a block ends with, where a line under it could have been one of its own:
 * the block itself, or the last of a list or a quote, which takes a line that is not indented as its paragraph's.
 */
private fun lastParagraph(block: ASTNode): ASTNode? = when (block.type) {
    MarkdownElementTypes.PARAGRAPH -> block

    MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST, MarkdownElementTypes.LIST_ITEM,
    MarkdownElementTypes.BLOCK_QUOTE,
    -> block.children.lastOrNull { it.type in blockTokens || !isToken(it.type) }?.let(::lastParagraph)

    else -> null
}

private val blockTokens = setOf(MarkdownElementTypes.PARAGRAPH, MarkdownTokenTypes.HORIZONTAL_RULE)

private fun isToken(type: IElementType) = (type as? MarkdownElementType)?.isToken ?: true
