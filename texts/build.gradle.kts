import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

// Everything the Android app and the web page say, in each language, with no toolkit: the strings.xml files in
// Android's format, compiled into Kotlin at build time, and what words a species, a fact or a caption from them.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/**
 * Writes the `<string>` and `<plurals>` resources of each values folder's strings.xml into a Kotlin source: an enum of
 * their names and a map of their texts by language, the default folder's as English. A text is resolved as aapt2
 * resolves it, so that each platform shows what Android would.
 */
abstract class WriteStrings : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun write() {
        val strings = sortedMapOf<String, MutableMap<String, String>>()
        val plurals = sortedMapOf<String, MutableMap<String, Map<String, String>>>()
        for (file in sources.files.sortedBy { it.path }) {
            val language = file.parentFile.name.removePrefix("values").removePrefix("-").ifEmpty { "en" }
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val elements = document.documentElement.childNodes.let { nodes ->
                (0 until nodes.length).map(nodes::item).filterIsInstance<Element>()
            }
            for (element in elements) {
                val name = element.getAttribute("name")
                val known = strings[language].orEmpty().keys + plurals[language].orEmpty().keys
                check(name !in known) { "$file: $name is defined twice for $language" }
                when (element.tagName) {
                    "string" -> strings.getOrPut(language, ::sortedMapOf)[name] = androidText(element.textContent)

                    "plurals" -> plurals.getOrPut(language, ::sortedMapOf)[name] =
                        element.getElementsByTagName("item").let { items ->
                            (0 until items.length).map { items.item(it) as Element }
                                .associate { it.getAttribute("quantity") to androidText(it.textContent) }
                        }

                    else -> error("$file: <${element.tagName}> is not a resource the texts take")
                }
            }
        }
        val stringKeys = strings.values.flatMap { it.keys }.toSortedSet()
        val pluralKeys = plurals.values.flatMap { it.keys }.toSortedSet()
        val source = buildString {
            append("package cz.loplex.dogvision.texts\n\n")
            append("// Written by the writeStrings task of texts/build.gradle.kts from the strings.xml files.\n\n")
            append("/** A string, named as in strings.xml. */\n")
            append("enum class Str {\n")
            stringKeys.forEach { append("    ${it.uppercase()},\n") }
            append("}\n\n")
            append("/** A plural, named as in strings.xml. */\n")
            append("enum class Plural {\n")
            pluralKeys.forEach { append("    ${it.uppercase()},\n") }
            append("}\n\n")
            append("/** Each language's strings. */\n")
            append("internal val STRINGS: Map<String, Map<Str, String>> = mapOf(\n")
            strings.forEach { (language, texts) ->
                append("    ${literal(language)} to mapOf(\n")
                texts.forEach { (name, text) -> append("        Str.${name.uppercase()} to ${literal(text)},\n") }
                append("    ),\n")
            }
            append(")\n\n")
            append("/** Each language's plurals, by quantity. */\n")
            append("internal val PLURALS: Map<String, Map<Plural, Map<String, String>>> = mapOf(\n")
            plurals.forEach { (language, texts) ->
                append("    ${literal(language)} to mapOf(\n")
                texts.forEach { (name, quantities) ->
                    val items = quantities.entries.joinToString { (quantity, text) ->
                        "${literal(quantity)} to ${literal(text)}"
                    }
                    append("        Plural.${name.uppercase()} to mapOf($items),\n")
                }
                append("    ),\n")
            }
            append(")\n")
        }
        val directory = output.get().asFile.resolve("cz/loplex/dogvision/texts")
        directory.deleteRecursively()
        directory.mkdirs()
        directory.resolve("Strings.kt").writeText(source)
    }

    /** [text] as a Kotlin string literal. */
    private fun literal(text: String): String = buildString {
        append('"')
        text.forEach { c ->
            append(
                when (c) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '$' -> "\\$"
                    '\n' -> "\\n"
                    '\t' -> "\\t"
                    else -> c
                },
            )
        }
        append('"')
    }

    /**
     * The text of a string resource as Android shows it: a backslash escapes the character after it (\n and \t stand
     * for a newline and a tab), double quotes are dropped and keep the whitespace between them, and elsewhere a run of
     * whitespace is one space and none is left at either end.
     */
    private fun androidText(raw: String): String {
        val text = StringBuilder()
        var quoted = false
        var space = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    if (space) text.append(' ')
                    space = false
                    i++
                    text.append(
                        when (raw[i]) {
                            'n' -> '\n'
                            't' -> '\t'
                            else -> raw[i]
                        },
                    )
                }

                c == '"' -> {
                    if (space) text.append(' ')
                    space = false
                    quoted = !quoted
                }

                c.isWhitespace() && !quoted -> space = text.isNotEmpty()

                else -> {
                    if (space) text.append(' ')
                    space = false
                    text.append(c)
                }
            }
            i++
        }
        return text.toString()
    }
}

val writeStrings = tasks.register<WriteStrings>("writeStrings") {
    description = "Writes the strings.xml files into a Kotlin source of the texts."
    sources.from(fileTree(file("strings")) { include("values/strings.xml", "values-*/strings.xml") })
    output = layout.buildDirectory.dir("generated/strings")
}

// The strings written are the files as they are, not code to hold to the style.
ktlint {
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    js {
        nodejs()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(writeStrings)
            dependencies {
                api(project(":core"))
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
