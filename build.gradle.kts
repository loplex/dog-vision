// Plugins are declared here once, so that every module shares one version of each.
plugins {
    // The root's own `check`, which the checks of the whole repository below are in.
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kover)
    id("cz.loplex.dogvision.packaging") apply false
    // The root sets Node.js up for the Kotlin/JS modules, so it takes it as they do.
    id("cz.loplex.dogvision.nodejs")
}

// Which lines and branches the JVM tests reach: Kover measures the modules that apply it,
// and `./gradlew koverHtmlReport` here writes all of them in one report, in build/reports/kover/html.
// Nothing fails on coverage.
// The web page's tests run in a browser, and the app's on a device, where Kover measures nothing.
dependencies {
    val measured =
        listOf(
            ":cli",
            ":core",
            ":ffmpeg",
            ":gl",
            ":gui-compose",
            ":gui-core",
            ":gui-swing",
            ":jvm-common",
            ":texts",
            ":ui",
        )
    measured.forEach { kover(project(it)) }
}

// tools/cache, what tools/fetch_msi_tools_on_linux.sh downloads,
// and tools/build, what the scripts in tools build, stay out of IntelliJ IDEA's index:
// the IDE's Gradle sync marks the idea plugin's module.excludeDirs excluded.
// Applied during a sync only, which the IDE announces with -Didea.sync.active=true,
// so that no other build gets the idea plugin.
if (gradle.startParameter.systemPropertiesArgs["idea.sync.active"] == "true") {
    pluginManager.apply("idea")
    val excluded = listOf(file("tools/cache"), file("tools/build"))
    the<org.gradle.plugins.ide.idea.model.IdeaModel>().module.excludeDirs.addAll(excluded)
}

// The npm packages of the Kotlin/JS modules, webpack, Karma, Mocha and what they need,
// are locked in gradle/package-lock.json,
// beside the catalog of the build's other versions, in place of Kotlin's own kotlin-js-store.
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    val npm = the<org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension>()
    npm.lockFileDirectory = layout.projectDirectory.dir("gradle")
}

// ktlint leaves a line that holds a comment and nothing else as long as it is, and nothing measures Markdown,
// so every line of either is held to .editorconfig's max_line_length for it here, in `check`.
fun maxLineLength(section: String) = file(".editorconfig").readLines()
    .dropWhile { it.trim() != section }.drop(1)
    .takeWhile { !it.startsWith("[") }
    .firstNotNullOf { Regex("""max_line_length\s*=\s*(\d+)""").matchEntire(it.trim())?.groupValues?.get(1)?.toInt() }
val checkLineLength = tasks.register("checkLineLength") {
    description = "Fails on a line of Kotlin or Markdown longer than .editorconfig's max_line_length for it."
    val root = rootDir
    val kotlinLimit = maxLineLength("[*.{kt,kts}]")
    val markdownLimit = maxLineLength("[*.md]")
    // What git tracks, and the new files it does not ignore, as tools/check_links.py reads them.
    fun listed(vararg patterns: String) = files(
        providers.exec {
            commandLine("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", *patterns)
        }.standardOutput.asText.map { listed ->
            listed.split('\u0000').filter { it.isNotEmpty() && File(root, it).isFile }
        },
    )
    val kotlin = listed("*.kt", "*.kts")
    val markdown = listed("*.md")
    inputs.files(kotlin, markdown)
    inputs.property("kotlinLimit", kotlinLimit)
    inputs.property("markdownLimit", markdownLimit)
    doLast {
        fun tooLong(source: File, number: Int, line: String, limit: Int, counted: String = "characters") =
            line.codePointCount(0, line.length).let {
                if (it > limit) "${source.relativeTo(root)}:$number: $it > $limit $counted" else null
            }
        val kotlinTooLong = kotlin.files.sorted().flatMap { source ->
            source.readLines().mapIndexedNotNull { i, line -> tooLong(source, i + 1, line, kotlinLimit) }
        }
        // Neither a code block's line nor a table's row can be broken, so they are left as long as they are;
        // nor can a link's target, so a line is measured as though its targets were not there.
        val fence = Regex("""^\s*(```|~~~)""")
        val tableRow = Regex("""^\s*\|""")
        val target = Regex("""(?<=\]\()[^)]*(?=\))|https?://\S+""")
        val markdownTooLong = markdown.files.sorted().flatMap { source ->
            var inCode = false
            source.readLines().mapIndexedNotNull { i, line ->
                if (fence.containsMatchIn(line)) inCode = !inCode
                if (inCode || fence.containsMatchIn(line) || tableRow.containsMatchIn(line)) {
                    null
                } else {
                    tooLong(source, i + 1, line.replace(target, ""), markdownLimit, "characters but links' targets")
                }
            }
        }
        val failures = kotlinTooLong + markdownTooLong
        if (failures.isNotEmpty()) throw GradleException(failures.joinToString("\n"))
    }
}

// docs/developing.md shows which module uses which in Graphviz's graphs,
// each drawn from its source in docs/modules, split so that fewer lines cross.
// Together they are held here to the dependencies the modules declare on each other, in `check`:
// a dependency is `api`, `implementation`, or `tests` when only tests have it.
// The modules' dependencies are read once all of them are evaluated.
val moduleUses = objects.setProperty<String>()
// A graph's bold modules are the programs it draws:
// the modules whose build makes something to run, an APK, a desktop package or a web page.
val programs = objects.setProperty<String>()
gradle.projectsEvaluated {
    moduleUses.set(
        subprojects.flatMap { module ->
            module.configurations.flatMap { configuration ->
                val name = configuration.name
                val kind = when {
                    "test" in name.lowercase() -> "tests"
                    name == "api" || name.endsWith("Api") -> "api"
                    else -> "implementation"
                }
                // The Android app's instrumented tests depend on the app itself, which is no use of another module.
                configuration.dependencies.withType<ProjectDependency>().filter { it.path != module.path }
                    .map { "${module.name} $kind ${it.path.removePrefix(":")}" }
            }
        }.toSet(),
    )
    programs.set(
        subprojects.filter { module ->
            val jsTargets = module.extensions
                .findByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()?.targets
                ?.withType<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget>().orEmpty()
            val webPage = jsTargets.any { target ->
                target.binaries.withType<org.jetbrains.kotlin.gradle.targets.js.ir.Executable>().isNotEmpty()
            }
            module.pluginManager.hasPlugin("com.android.application") ||
                module.pluginManager.hasPlugin("cz.loplex.dogvision.packaging") ||
                webPage
        }.map { it.name }.toSet(),
    )
}

// The comment each SVG in docs/modules carries before its <svg> element: the name of the .dot it is drawn from,
// and that file's SHA-256 with its lines ended by \n, as a checkout on Windows may end them by \r\n.
// checkModuleGraph holds the SVG to its .dot through it, with no Graphviz to run.
object ModuleGraphSource {
    fun text(dot: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(dot.readText().replace("\r\n", "\n").toByteArray())
        return "Drawn from ${dot.name}, SHA-256 ${digest.joinToString("") { "%02x".format(it) }}"
    }

    fun comment(dot: File): String = "<!-- ${text(dot)} -->"
}

// The SVG dot writes, tidied: each element on a line of its own, indented by two spaces,
// and without the namespace declarations nothing in it uses, such as the xlink one dot writes into every graph;
// [comment] goes before <svg>.
object ModuleGraphSvg {
    fun tidied(svg: String, comment: String): String {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // The DOCTYPE names the SVG 1.1 DTD, which is not fetched.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val document = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(svg)))
        val blanks = mutableListOf<org.w3c.dom.Node>()
        val prefixes = mutableSetOf<String>()
        fun visit(node: org.w3c.dom.Node) {
            if (node.nodeType == org.w3c.dom.Node.TEXT_NODE && node.nodeValue.isBlank()) blanks += node
            node.prefix?.let { prefixes += it }
            val attributes = node.attributes
            if (attributes != null) {
                for (i in 0 until attributes.length) {
                    attributes.item(i).prefix?.takeIf { it != "xmlns" }?.let { prefixes += it }
                }
            }
            val children = node.childNodes
            for (i in 0 until children.length) visit(children.item(i))
        }
        visit(document)
        blanks.forEach { it.parentNode.removeChild(it) }
        val root = document.documentElement
        val declarations = root.attributes
        (0 until declarations.length).map { declarations.item(it) }
            .filter { it.prefix == "xmlns" && it.localName !in prefixes }
            .forEach { root.removeAttributeNode(it as org.w3c.dom.Attr) }
        // What goes before <svg> is written here, as the serializer puts a DOCTYPE after the comments and runs them on.
        val out = java.io.StringWriter()
        if (svg.startsWith("<?xml")) out.appendLine(svg.substringBefore("?>") + "?>")
        document.doctype?.let { out.appendLine("<!DOCTYPE ${it.name} PUBLIC \"${it.publicId}\"\n \"${it.systemId}\">") }
        val children = document.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == org.w3c.dom.Node.COMMENT_NODE) out.appendLine("<!--${child.nodeValue}-->")
        }
        out.appendLine("<!-- $comment -->")
        val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes")
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        transformer.transform(javax.xml.transform.dom.DOMSource(root), javax.xml.transform.stream.StreamResult(out))
        return out.toString()
    }
}
val moduleGraphs = layout.projectDirectory.dir("docs/modules")
tasks.register("drawModuleGraphs") {
    description = "Draws each docs/modules/*.dot into its SVG, with Graphviz's dot, which has to be on the PATH."
    val sources = fileTree(moduleGraphs) { include("*.dot") }
    doLast {
        sources.files.sortedBy { it.name }.forEach { dot ->
            val process = try {
                ProcessBuilder("dot", "-Tsvg", dot.path).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            } catch (e: java.io.IOException) {
                throw GradleException("Graphviz's dot does not run; is it installed and on the PATH?", e)
            }
            val svg = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) throw GradleException("dot failed on ${dot.name}")
            val target = File(dot.parentFile, dot.nameWithoutExtension + ".svg")
            target.writeText(ModuleGraphSvg.tidied(svg, ModuleGraphSource.text(dot)))
        }
    }
}
val checkModuleGraph = tasks.register("checkModuleGraph") {
    description = "Fails when the graphs of the modules in docs/modules differ from the dependencies, or their SVGs " +
        "from them."
    val doc = file("docs/developing.md")
    val sources = fileTree(moduleGraphs) { include("*.dot", "*.svg") }
    val uses = moduleUses
    val runnable = programs
    inputs.file(doc)
    inputs.files(sources)
    inputs.property("moduleUses", uses)
    inputs.property("programs", runnable)
    doLast {
        val files = sources.files.sortedBy { it.name }
        val dots = files.filter { it.extension == "dot" }
        if (dots.isEmpty()) throw GradleException("docs/modules: no .dot")
        val problems = mutableListOf<String>()
        // A module's name, in quotes where it holds a hyphen, as dot asks.
        val module = """"?([\w-]+)"?"""
        // The layers' titles, top to bottom: `layer_a -> layer_b -> …`.
        val titles = Regex("""\s*(layer_\w+(?: -> layer_\w+)+)""")
        // A layer's floor: `{ rank=same; layer_a -> { m; "n-o" } [style=invis] }`.
        val floor = Regex("""\s*\{ rank=same; (layer_\w+) -> \{ ([^}]*) } \[style=invis] }""")
        // A program's label: its name in bold, then what it is in small letters.
        val bold = Regex("""\s*$module \[label=<<b>([\w-]+)</b><br/><font [^>]*>[^<]+</font>>]""")
        val arrow = Regex("""\s*$module -> $module(?: \[([^\]]*)])?""")
        // The look of each kind of arrow; core's tests point up, where an arrow must not place the layers.
        val kinds = mapOf(
            "" to "implementation",
            "penwidth=2.5" to "api",
            "style=dashed" to "tests",
            "style=dashed, constraint=false" to "tests",
        )
        val drawn = mutableSetOf<String>()
        dots.forEach { dot ->
            val name = dot.name
            val lines = dot.readLines()
            val layers = lines.firstNotNullOfOrNull { titles.matchEntire(it)?.groupValues?.get(1)?.split(" -> ") }
            if (layers == null) problems += "$name: no line of the layers' titles, `layer_a -> layer_b -> …`"
            val layerOf = lines.mapNotNull { floor.matchEntire(it)?.groupValues }.flatMap { (_, layer, modules) ->
                modules.split(";").map { it.trim().removeSurrounding("\"") to layer }
            }.toMap()
            val arrows = lines.filter { "->" in it && titles.matchEntire(it) == null && floor.matchEntire(it) == null }
                .mapNotNull { line ->
                    val match = arrow.matchEntire(line)?.groupValues
                    val kind = match?.let { kinds[it[3]] }
                    if (kind == null) problems += "$name: not an arrow of a known kind: ${line.trim()}"
                    if (match == null || kind == null) null else Triple(match[1], kind, match[2])
                }
            arrows.forEach { (from, kind, to) ->
                drawn += "$from $kind $to"
                val unplaced = listOf(from, to).filter { it !in layerOf }
                unplaced.forEach { problems += "$name: on no layer's floor: $it" }
                // The layers, top to bottom: a module's code uses only modules in a layer below its own.
                if (unplaced.isEmpty() && layers != null && kind != "tests" &&
                    layers.indexOf(layerOf.getValue(to)) <= layers.indexOf(layerOf.getValue(from))
                ) {
                    problems += "$name: not to a layer below: $from $kind $to"
                }
            }
            val modules = arrows.flatMap { listOf(it.first, it.third) }.toSet()
            val labels = lines.mapNotNull { bold.matchEntire(it)?.groupValues }
            labels.filter { it[1] != it[2] }.forEach { problems += "$name: ${it[1]} labelled as ${it[2]}" }
            val bolded = labels.map { it[1] }.toSet()
            val shouldBe = modules intersect runnable.get()
            problems += (shouldBe - bolded).sorted().map { "$name: not bold: $it" } +
                (bolded - shouldBe).sorted().map { "$name: bold, but no program drawn there: $it" }
            val svg = File(dot.parentFile, dot.nameWithoutExtension + ".svg")
            if (!svg.isFile || ModuleGraphSource.comment(dot) !in svg.readText()) {
                problems += "${svg.name} is not drawn from $name as it is: run ./gradlew drawModuleGraphs"
            }
        }
        files.filter { it.extension == "svg" && File(it.parentFile, it.nameWithoutExtension + ".dot") !in dots }
            .forEach { problems += "${it.name}: no .dot to draw it from" }
        // Every graph is in docs/developing.md, as an image.
        val shown = Regex("""!\[[^\]]*]\(modules/([\w-]+)\.svg\)""").findAll(doc.readText())
            .map { it.groupValues[1] }.toSet()
        problems += (dots.map { it.nameWithoutExtension }.toSet() - shown).sorted()
            .map { "$it.svg: not shown in docs/developing.md" }
        val declared = uses.get()
        problems += (declared - drawn).sorted().map { "not drawn: $it" } +
            (drawn - declared).sorted().map { "drawn, not declared: $it" }
        if (problems.isNotEmpty()) {
            throw GradleException("docs/modules' graphs of the modules:\n" + problems.distinct().joinToString("\n"))
        }
    }
}

// docs/building.md's table in "Every artifact at once" says which folder each artifact lands in;
// it is held here to the outputs that the tasks packageAll runs declare, in `check`:
// each output is a folder of the table or lies directly in one, and each folder holds one.
// A folder `…/rpm` is beside the one before it in its cell.
// The tasks' outputs are read once every module is evaluated, by task,
// but for the work files in build/intermediates, Android's plugin's, and in build/tmp, Gradle's.
val artifactOutputs = objects.mapProperty<String, List<String>>()
gradle.projectsEvaluated {
    artifactOutputs.set(
        subprojects.flatMap { module ->
            val packageAll = module.tasks.findByName("packageAll") ?: return@flatMap emptyList()
            packageAll.taskDependencies.getDependencies(packageAll).map { task ->
                task.path to task.outputs.files.files.map { it.relativeTo(rootDir).invariantSeparatorsPath }
                    .filter { "/build/intermediates/" !in it && "/build/tmp/" !in it }.sorted()
            }
        }.toMap(),
    )
}
val checkArtifactFolders = tasks.register("checkArtifactFolders") {
    description = "Fails when docs/building.md's folders of the artifacts differ from what packageAll's tasks write."
    val doc = file("docs/building.md")
    val outputs = artifactOutputs
    inputs.file(doc)
    inputs.property("artifactOutputs", outputs)
    doLast {
        val section = doc.readText().substringAfter("\n## Every artifact at once\n", "").substringBefore("\n## ")
        val rows = section.lines().filter { it.startsWith("|") }.drop(2)
        if (rows.isEmpty()) throw GradleException("docs/building.md: no table under \"Every artifact at once\"")
        val folders = rows.flatMap { row ->
            val cell = row.split("|").getOrNull(3)?.trim()
                ?: throw GradleException("docs/building.md: a row of the artifacts has no folder: $row")
            val paths = cell.split(",").map { it.trim().removeSurrounding("`") }
            paths.map { path ->
                if (path.startsWith("…/")) paths.first().substringBeforeLast("/") + path.removePrefix("…") else path
            }
        }.toSet()
        val written = outputs.get()
        val all = written.values.flatten()
        val problems = written.filterValues { it.isEmpty() }.keys.sorted()
            .map { "$it declares no output, so no folder of it can be checked" } +
            written.toSortedMap().flatMap { (task, files) ->
                files.filter { it !in folders && it.substringBeforeLast("/") !in folders }
                    .map { "$task writes $it, in no folder of the table" }
            } +
            folders.filter { folder -> all.none { it == folder || it.substringBeforeLast("/") == folder } }.sorted()
                .map { folder -> "the table's folder $folder holds nothing packageAll writes" }
        if (problems.isNotEmpty()) {
            throw GradleException("docs/building.md's folders of the artifacts:\n" + problems.joinToString("\n"))
        }
    }
}

// The checks of the whole repository, in the root's `check`, which `./gradlew check` runs beside every module's.
// They include build-logic's, a build of its own, which `check` here does not reach otherwise:
// its ktlint and its tests.
tasks.named("check") {
    val buildLogicCheck = gradle.includedBuild("build-logic").task(":check")
    dependsOn(checkLineLength, checkModuleGraph, checkArtifactFolders, buildLogicCheck)
}
