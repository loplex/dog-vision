// Plugins are declared here once, so that every module shares one version of each.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint) apply false
    id("cz.loplex.dogvision.packaging") apply false
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

// The Kotlin/JS modules download Node.js from the repository declared in settings.gradle.kts,
// which is where the build declares every repository, instead of adding one of their own;
// the root project sets it up for them.
allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().downloadBaseUrl = null
    }
}

// Every module's Kotlin, the build scripts' included, is held to .editorconfig by ktlint in `check`.
val ktlintPlugin = libs.plugins.ktlint.get().pluginId
val ktlintVersion = libs.versions.ktlint.cli.get()
subprojects {
    @Suppress("AvoidApplyPluginMethod")
    apply(plugin = ktlintPlugin)
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version = ktlintVersion
    }
}

// detekt, in each module that applies it, in `check`: its default rules, as detekt.yml changes them,
// over every source set, with the types of the JVM and Android compilations where it has them.
// Code the build generates is not checked.
subprojects {
    plugins.withId("dev.detekt") {
        configure<dev.detekt.gradle.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            config.from(rootProject.file("detekt.yml"))
        }
        tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
            exclude { it.file.invariantSeparatorsPath.contains("/build/") }
        }
        // The typed tasks cover commonMain, jvmMain and androidMain through their compilations;
        // the JS source sets, which no JVM compilation takes, are checked alone.
        val checked = Regex("detekt(Main|Test|Dev)(Jvm|Android)|detekt(Js|Web)(Main|Test)SourceSet")
        tasks.named("check") { dependsOn(tasks.matching { checked.matches(it.name) }) }
    }
}

// ktlint leaves a line that holds a comment and nothing else as long as it is, and nothing measures Markdown,
// so every line of either is held to .editorconfig's max_line_length for it here, in each module's `check`.
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

// docs/developing.md draws which module uses which, in Mermaid's graphs, split so that fewer lines cross;
// together they are held here to the dependencies the modules declare on each other, in each module's `check`.
// An arrow is `==>` for api, `-->` for implementation, and `-.->` for a dependency that only tests have;
// `~~~`, which only places a layer, is no dependency.
// The modules' dependencies are read once all of them are evaluated.
val moduleUses = objects.setProperty<String>()
// A graph's `class ... program` line makes bold the programs it draws:
// the modules whose build makes something to run, an APK, a desktop package or a web page.
val programs = objects.setProperty<String>()
gradle.projectsEvaluated {
    moduleUses.set(
        subprojects.flatMap { module ->
            module.configurations.flatMap { configuration ->
                val name = configuration.name
                val arrow = when {
                    "test" in name.lowercase() -> "-.->"
                    name == "api" || name.endsWith("Api") -> "==>"
                    else -> "-->"
                }
                // The Android app's instrumented tests depend on the app itself, which is no use of another module.
                configuration.dependencies.withType<ProjectDependency>().filter { it.path != module.path }
                    .map { "${module.name} $arrow ${it.path.removePrefix(":")}" }
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
val checkModuleGraph = tasks.register("checkModuleGraph") {
    description = "Fails when the graphs of the modules in docs/developing.md differ from their dependencies."
    val doc = file("docs/developing.md")
    val uses = moduleUses
    val runnable = programs
    inputs.file(doc)
    inputs.property("moduleUses", uses)
    inputs.property("programs", runnable)
    doLast {
        val graphs = Regex("^```mermaid\n(.*?)^```", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .findAll(doc.readText()).map { it.groupValues[1] }.toList()
        if (graphs.isEmpty()) throw GradleException("docs/developing.md: no ```mermaid block")
        val arrows = graphs.map { graph ->
            graph.lines().mapNotNull { Regex("""\s*([\w-]+) (==>|-->|-\.->) ([\w-]+)""").matchEntire(it)?.groupValues }
        }
        val drawn = arrows.flatten().map { it.drop(1).joinToString(" ") }.toSet()
        val declared = uses.get()
        val problems = (declared - drawn).sorted().map { "not drawn: $it" } +
            (drawn - declared).sorted().map { "drawn, not declared: $it" } +
            graphs.zip(arrows).withIndex().flatMap { (i, graphAndArrows) ->
                val (graph, graphArrows) = graphAndArrows
                val modules = graphArrows.flatMap { listOf(it[1], it[3]) }.toSet()
                val bold = graph.lines()
                    .mapNotNull { Regex("""\s*class ([\w,-]+) program""").matchEntire(it)?.groupValues?.get(1) }
                    .flatMap { it.split(",") }.toSet()
                val shouldBe = modules intersect runnable.get()
                (shouldBe - bold).sorted().map { "graph ${i + 1}: not bold: $it" } +
                    (bold - shouldBe).sorted().map { "graph ${i + 1}: bold, but no program drawn there: $it" }
            }
        if (problems.isNotEmpty()) {
            throw GradleException("docs/developing.md's graphs of the modules:\n" + problems.joinToString("\n"))
        }
    }
}

// docs/building.md's table in "Every artifact at once" says which folder each artifact lands in;
// it is held here to the outputs that the tasks packageAll runs declare, in each module's `check`:
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

// build-logic is a build of its own, which `check` here does not reach otherwise:
// its ktlint and its tests, in each module's `check`.
val buildLogicCheck = gradle.includedBuild("build-logic").task(":check")
subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(checkLineLength, checkModuleGraph, checkArtifactFolders, buildLogicCheck)
    }
}
