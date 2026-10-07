package cz.loplex.dogvision.packaging

import org.w3c.dom.Element
import java.io.File
import java.io.Serializable
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A part of an artifact that is not this project's own:
 * a library, or what a native library holds besides its own code.\
 * [licence] is an SPDX expression; [text] is the file in packaging/licenses that holds its licence's text;
 * [files] are the names of the files the artifact holds it in, a JAR or a native library.
 */
data class ThirdPartyPart(
    val name: String,
    val version: String,
    val licence: String,
    val holder: String,
    val text: String,
    val files: List<String>,
) : Serializable

/** [parts] a line each, their fields apart by tabs and the files by commas, for [readParts] to read back. */
fun writeParts(parts: List<ThirdPartyPart>): String = parts.joinToString("") { part ->
    val fields = listOf(part.name, part.version, part.licence, part.holder, part.text, part.files.joinToString(","))
    check(fields.none { '\t' in it || '\n' in it } && part.files.none { ',' in it }) { "$part cannot be written" }
    fields.joinToString("\t") + "\n"
}

/** [parts] with each part listed more than once, as two launchers' lists both hold it, once, in all its files. */
fun mergeParts(parts: List<ThirdPartyPart>): List<ThirdPartyPart> =
    parts.groupBy { it.copy(files = emptyList()) }.map { (part, copies) ->
        part.copy(files = copies.flatMap { it.files }.distinct())
    }

/** The parts [writeParts] wrote in [text]. */
fun readParts(text: String): List<ThirdPartyPart> = text.lines().filter { it.isNotEmpty() }.map { line ->
    val fields = line.split('\t')
    check(fields.size == 6) { "Not a part: $line" }
    ThirdPartyPart(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5].split(','))
}

/**
 * The note that a Java runtime of Eclipse Temurin's [version] lies in [folder] of an artifact,
 * with its own licence and notices in its legal folder, which jlink keeps in every runtime it links.
 */
fun javaRuntimeNote(folder: String, version: String): String =
    "The Java runtime in $folder is Eclipse Temurin $version. Its licence, GPL-2.0-only WITH " +
        "Classpath-exception-2.0, and the notices of what it holds are in $folder/legal."

/** The text of every licence Apache-2.0 alone names, the one of the Apache Software Foundation. */
const val APACHE_TEXT = "Apache-2.0.txt"

/**
 * The SPDX identifier of a licence as a POM names it, by [name] and [url], or null where it is none this table knows.\
 * Each spelling below is one a library the packages bundle uses.
 */
fun spdxOf(name: String?, url: String?): String? = when {
    url != null && APACHE_URL.matches(url) -> "Apache-2.0"
    name in APACHE_NAMES -> "Apache-2.0"
    name == "BSD-3-Clause" || name == "BSD 3-Clause License" -> "BSD-3-Clause"
    else -> null
}

private val APACHE_URL = Regex("""https?://www\.apache\.org/licenses/LICENSE-2\.0(\.txt|\.html)?""")
private val APACHE_NAMES =
    setOf("Apache-2.0", "The Apache Software License, Version 2.0", "The Apache License, Version 2.0")

/**
 * The holder of a library whose POM names a person rather than who holds the copyright,
 * and the text of a licence other than Apache-2.0, which names its holder; keyed by the library's group.
 */
private val GROUP_HOLDERS = mapOf(
    "org.lwjgl" to "Lightweight Java Game Library",
    // ANGLE's DLLs, which Nucleus builds and publishes.
    "dev.nucleusframework" to "The ANGLE Project Authors",
)
private val GROUP_TEXTS = mapOf("org.lwjgl" to "lwjgl.txt", "dev.nucleusframework" to "angle.txt")

/**
 * The part a library is, read from its [pom],
 * as the module [group]:[module]:[version] whose JAR the artifact holds as [files].\
 * Fails where the POM names a licence [spdxOf] does not know, or no holder: no organization and no developer.
 */
fun pomPart(group: String, module: String, version: String, pom: File, files: List<String>): ThirdPartyPart {
    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom).documentElement
    val licences = root.children("licenses").flatMap { it.children("license") }.map {
        it.child("name") to it.child("url")
    }
    check(licences.isNotEmpty()) { "$group:$module:$version names no licence in its POM" }
    val spdx = licences.map { (name, url) ->
        checkNotNull(spdxOf(name, url)) {
            "$group:$module:$version is under a licence the build does not know: $name, $url. Add it to spdxOf in " +
                "build-logic's ThirdParty.kt, with its text in packaging/licenses."
        }
    }.distinct()
    val developers = root.children("developers").flatMap { it.children("developer") }
    val holder = GROUP_HOLDERS[group]
        ?: root.children("organization").firstNotNullOfOrNull { it.child("name") }
        ?: developers.mapNotNull { it.child("organization") }.distinct().joinToString(", ").ifEmpty { null }
        ?: developers.mapNotNull { it.child("name") }.distinct().joinToString(", ").ifEmpty { null }
        ?: error("$group:$module:$version names no organization and no developer in its POM")
    val licence = spdx.joinToString(" OR ")
    val text = GROUP_TEXTS[group] ?: if (spdx == listOf("Apache-2.0")) {
        APACHE_TEXT
    } else {
        error(
            "$group:$module:$version is under $licence, whose text the build does not have. Add it to GROUP_TEXTS in " +
                "build-logic's ThirdParty.kt, with its text in packaging/licenses.",
        )
    }
    return ThirdPartyPart("$group:$module", version, licence, holder, text, files)
}

private fun Element.children(tag: String): List<Element> =
    (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>().filter { it.tagName == tag }

private fun Element.child(tag: String): String? = children(tag).firstOrNull()?.textContent?.trim()

/**
 * What the natives of a JAR hold besides their own code, which no POM says, keyed by the JAR's file name:
 * measured in the libraries themselves and, for skiko, in the Skia it builds
 * (skiko 0.150.1 builds Skia m150-1f14f1166a, whose DEPS pins each library;
 * Skia's BUILD.gn adds SPIRV-Cross and D3D12MemoryAllocator for Direct3D).\
 * A library raised to a new version is to be measured again.
 */
val EMBEDDED_PARTS: List<Pair<Regex, List<ThirdPartyPart>>> = listOf(
    Regex("""skiko-awt-runtime-linux-x64-.*\.jar""") to skikoParts("libskiko-linux-x64.so", windows = false),
    Regex("""skiko-awt-runtime-windows-x64-.*\.jar""") to skikoParts("skiko-windows-x64.dll", windows = true),
    Regex("""lwjgl-[0-9.]+-natives-linux\.jar""") to listOf(libffi("liblwjgl.so")),
    Regex("""lwjgl-[0-9.]+-natives-windows\.jar""") to listOf(libffi("lwjgl.dll")),
    Regex("""lwjgl-glfw-[0-9.]+-natives-windows\.jar""") to listOf(
        ThirdPartyPart("GLFW", "3.5.1", "Zlib", "Marcus Geelnard, Camilla Löwy", "glfw.txt", listOf("glfw.dll")),
    ),
    // ANGLE chromium/8037, whose own licence its POM names.
    Regex("""nucleus\.angle-natives-.*\.jar""") to listOf(
        ThirdPartyPart(
            "Abseil",
            "chromium/8037's",
            "Apache-2.0",
            "The Abseil Authors",
            APACHE_TEXT,
            listOf("libEGL.dll", "libGLESv2.dll"),
        ),
    ),
)

private fun libffi(file: String) =
    ThirdPartyPart("libffi", "3.8.0", "MIT", "Anthony Green, Red Hat, Inc and others", "libffi.txt", listOf(file))

private fun skikoParts(file: String, windows: Boolean): List<ThirdPartyPart> {
    fun part(name: String, version: String, licence: String, holder: String, text: String) =
        ThirdPartyPart(name, version, licence, holder, text, listOf(file))
    val shared = listOf(
        part("Skia", "m150-1f14f1166a", "BSD-3-Clause", "Google Inc.", "skia.txt"),
        part("HarfBuzz", "9cb1fee510", "MIT-Modern-Variant", "The HarfBuzz authors", "harfbuzz.txt"),
        part("ICU", "364118a1d9", "Unicode-3.0", "Unicode, Inc.", "icu.txt"),
        part("libpng", "1.6.56", "libpng-2.0", "Cosmin Truta and the libpng authors", "libpng.txt"),
        part("zlib", "1.3.0.1", "Zlib", "Jean-loup Gailly and Mark Adler", "zlib.txt"),
        part(
            "Expat",
            "2.7.4",
            "MIT",
            "Thai Open Source Software Center Ltd, Clark Cooper, and the Expat maintainers",
            "expat.txt",
        ),
        part(
            "libjpeg-turbo",
            "3.1.0",
            "IJG AND BSD-3-Clause AND Zlib",
            "The libjpeg-turbo Project, the Independent JPEG Group",
            "libjpeg-turbo.txt",
        ),
        part("libwebp", "845d5476a8", "BSD-3-Clause", "Google Inc.", "libwebp.txt"),
        part("Wuffs", "e3f919ccfe", "Apache-2.0", "The Wuffs Authors", APACHE_TEXT),
    )
    val own = if (windows) {
        listOf(
            part("SPIRV-Cross", "b8fcf307f1", "Apache-2.0", "Arm Limited", APACHE_TEXT),
            part(
                "D3D12MemoryAllocator",
                "169895d529",
                "MIT",
                "Advanced Micro Devices, Inc.",
                "d3d12memoryallocator.txt",
            ),
        )
    } else {
        listOf(
            part("FreeType", "264b5fbf5b", "FTL", "The FreeType Project", "freetype.txt"),
            part("DNG SDK", "dbe0a67645", "LicenseRef-DNG-SDK", "Adobe Systems Incorporated", "dng_sdk.txt"),
            part("piex", "bb217acdca", "Apache-2.0", "Google Inc.", APACHE_TEXT),
        )
    }
    return shared + own
}

/** The texts that go beside a text, by file name: the IJG's licence of libjpeg-turbo, and libwebp's patent grant. */
private val COMPANION_TEXTS = mapOf(
    "libjpeg-turbo.txt" to listOf("libjpeg-turbo-ijg.txt"),
    "libwebp.txt" to listOf("libwebp-patents.txt"),
)

/** [text] and its companions, read from [texts], one after another. */
private fun fullText(text: String, texts: File): String =
    (listOf(text) + COMPANION_TEXTS[text].orEmpty()).joinToString("\n\n") { File(texts, it).readText().trimEnd() }

/** The SPDX expression of a package of this project's own [ownLicence] with [parts] in it, each licence once. */
fun spdxExpression(ownLicence: String, parts: List<ThirdPartyPart>): String =
    (listOf(ownLicence) + parts.flatMap { it.licence.split(" AND ") }).distinct().joinToString(" AND ") {
        if (" OR " in it) "($it)" else it
    }

/**
 * The text that lists [parts] of [artifact],
 * whose own code is under [ownLicence], with each licence's full text from [texts];
 * [notes] are paragraphs about what has notices of its own, such as a Java runtime's legal folder.
 */
fun thirdPartyNotices(
    artifact: String,
    ownLicence: String,
    parts: List<ThirdPartyPart>,
    texts: File,
    notes: List<String>,
): String = buildString {
    appendLine("$artifact holds, besides its own code under $ownLicence (LICENSE), what follows.")
    appendLine()
    @Suppress("DestructuringDeclaration")
    for (part in parts.sortedBy { it.name.lowercase() }) {
        appendLine("${part.name} ${part.version}, ${part.licence}, ${part.holder}; in ${part.files.joinToString(", ")}")
    }
    for (note in notes) {
        appendLine()
        appendLine(note)
    }
    for (text in parts.map { it.text }.distinct().sorted()) {
        val names = parts.filter { it.text == text }.map { it.name }.distinct().sorted()
        appendLine()
        appendLine("=".repeat(LINE))
        appendLine(wrapped("The licence of ${names.joinToString(", ")}"))
        appendLine("=".repeat(LINE))
        appendLine()
        appendLine(fullText(text, texts))
    }
}

private const val LINE = 100

/** [text] broken at its spaces into lines of at most [LINE] characters, where its words allow. */
private fun wrapped(text: String): String = text.split(' ').fold(listOf("")) { lines, word ->
    val last = lines.last()
    if (last.isEmpty()) {
        lines.dropLast(1) + word
    } else if (last.length + 1 + word.length <= LINE) {
        lines.dropLast(1) + "$last $word"
    } else {
        lines + word
    }
}.joinToString("\n")

/**
 * The deb's copyright file in Debian's machine-readable format, DEP-5:
 * the package's own files under [ownLicence], a short name Debian's common-licenses folder knows,
 * then a paragraph for each file of [parts] with every holder and licence of what it holds,
 * and a paragraph for each licence: Apache-2.0's points at common-licenses, the others hold their text from [texts].\
 * Each part's files are matched in any folder.
 */
fun dep5Copyright(
    upstreamName: String,
    contact: String,
    source: String,
    ownHolder: String,
    ownLicence: String,
    parts: List<ThirdPartyPart>,
    texts: File,
): String = buildString {
    appendLine("Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/")
    appendLine("Upstream-Name: $upstreamName")
    appendLine("Upstream-Contact: $contact")
    appendLine("Source: $source")
    appendLine()
    appendLine("Files: *")
    appendLine("Copyright: $ownHolder")
    appendLine("License: $ownLicence")
    val files = parts.flatMap { it.files }.distinct().sorted()
    for (file in files) {
        val inFile = parts.filter { file in it.files }
        appendLine()
        appendLine("Files: */$file")
        appendLine("Copyright: " + inFile.map { it.holder }.distinct().joinToString("\n "))
        appendLine("License: " + inFile.map { licenceName(it) }.distinct().joinToString(" and "))
        appendLine("Comment: " + inFile.joinToString(", ") { "${it.name} ${it.version}" })
    }
    for (name in DEBIAN_COMMON.keys.filter { it == ownLicence }) {
        appendLine()
        appendLine("License: $name")
        appendLine(
            " On Debian systems, its complete text is in /usr/share/common-licenses/${DEBIAN_COMMON.getValue(name)}.",
        )
    }
    for ((name, text) in parts.associate { licenceName(it) to it.text }.toSortedMap()) {
        appendLine()
        appendLine("License: $name")
        val common = DEBIAN_COMMON[name]
        if (common != null) {
            appendLine(" On Debian systems, its complete text is in /usr/share/common-licenses/$common.")
        } else {
            for (line in fullText(text, texts).lines()) appendLine(if (line.isBlank()) " ." else " $line")
        }
    }
}

/** Debian's short names that /usr/share/common-licenses holds the text of, and the file it holds it in. */
private val DEBIAN_COMMON = mapOf("GPL-3+" to "GPL-3", "Apache-2.0" to "Apache-2.0")

/**
 * A part's licence as a DEP-5 short name: Apache-2.0's, the one text Debian has;
 * any other text names its holder, so the name is the licence's followed by the text's, as BSD-3-Clause-skia.
 */
private fun licenceName(part: ThirdPartyPart): String = if (part.text ==
    APACHE_TEXT
) {
    "Apache-2.0"
} else {
    part.licence.substringBefore(' ') + "-" + part.text.removeSuffix(".txt")
}
