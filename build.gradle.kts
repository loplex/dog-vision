// Plugins are declared here once, so that every module shares one version of each.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.ktlint) apply false
}

// The Kotlin/JS modules download Node.js from the repository declared in settings.gradle.kts, which is where the build
// declares every repository, instead of adding one of their own; the root project sets it up for them.
allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().downloadBaseUrl = null
    }
}

// IntelliJ IDEA asks every project for Compose Hot Reload's model when it syncs, and marks the sync failed over each
// one that has no builder for it. The Hot Reload plugin registers its builder only where Compose Multiplatform applies
// the plugin, so every other project answers with what that builder would report there: no hot run task. Compose
// applies the plugin after the afterEvaluate hooks registered here have run, and a second builder for the model fails
// the sync, so which projects lack it is decided once all of them are evaluated.
object NoHotRunForIdea : org.gradle.tooling.provider.model.ToolingModelBuilder {
    override fun canBuild(modelName: String) =
        modelName == org.jetbrains.compose.reload.gradle.idea.IdeaComposeHotReloadModel::class.java.name

    override fun buildAll(modelName: String, project: Project) =
        org.jetbrains.compose.reload.gradle.idea.IdeaComposeHotReloadModel(
            org.jetbrains.compose.reload.core.HOT_RELOAD_VERSION,
            emptyList(),
        )
}
abstract class HotReloadModelForIdea @Inject constructor(
    private val registry: org.gradle.tooling.provider.model.ToolingModelBuilderRegistry,
) : Plugin<Project> {
    override fun apply(project: Project) = project.gradle.projectsEvaluated {
        if (!project.pluginManager.hasPlugin("org.jetbrains.compose.hot-reload")) registry.register(NoHotRunForIdea)
    }
}
allprojects {
    apply<HotReloadModelForIdea>()
}

// Every module's Kotlin, the build scripts' included, is held to .editorconfig by ktlint in `check`.
val ktlintPlugin = libs.plugins.ktlint.get().pluginId
val ktlintVersion = libs.versions.ktlint.cli.get()
subprojects {
    apply(plugin = ktlintPlugin)
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version = ktlintVersion
    }
}

// ktlint leaves a line that holds a comment and nothing else as long as it is, so every line of Kotlin is held to
// .editorconfig's max_line_length here, in each module's `check`.
val maxLineLength = file(".editorconfig").readLines()
    .dropWhile { it.trim() != "[*.{kt,kts}]" }.drop(1)
    .takeWhile { !it.startsWith("[") }
    .firstNotNullOf { Regex("""max_line_length\s*=\s*(\d+)""").matchEntire(it.trim())?.groupValues?.get(1)?.toInt() }
val checkLineLength = tasks.register("checkLineLength") {
    description = "Fails on a line of Kotlin longer than .editorconfig's max_line_length, comments included."
    val root = rootDir
    val limit = maxLineLength
    val sources = fileTree(root) {
        include("**/*.kt", "**/*.kts")
        exclude("**/build/**", ".gradle/**", ".kotlin/**", "NOTES/**")
    }
    inputs.files(sources)
    inputs.property("maxLineLength", limit)
    doLast {
        val tooLong = sources.files.sorted().flatMap { source ->
            source.readLines().mapIndexedNotNull { i, line ->
                val length = line.codePointCount(0, line.length)
                if (length > limit) "${source.relativeTo(root)}:${i + 1}: $length > $limit characters" else null
            }
        }
        if (tooLong.isNotEmpty()) throw GradleException(tooLong.joinToString("\n"))
    }
}
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(checkLineLength) }
}
