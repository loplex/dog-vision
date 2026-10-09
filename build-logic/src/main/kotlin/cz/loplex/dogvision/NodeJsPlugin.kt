package cz.loplex.dogvision

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec

/**
 * Applied by every Kotlin/JS module and the root project, which sets Node.js up for them:
 * each downloads Node.js from the repository declared in settings.gradle.kts,
 * which is where the build declares every repository, instead of adding one of its own.
 */
@Suppress("unused", "RedundantSuppression") // named only by implementationClass in build.gradle.kts
class NodeJsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withType(org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin::class.java) {
            project.extensions.getByType(NodeJsEnvSpec::class.java).downloadBaseUrl.set(null as String?)
        }
    }
}
