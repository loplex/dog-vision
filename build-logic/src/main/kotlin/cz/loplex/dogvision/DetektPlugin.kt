package cz.loplex.dogvision

import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Applied by every module detekt checks:
 * detekt of the catalog's version, in `check`, with its default rules as detekt.yml changes them,
 * over every source set, with the types of the JVM and Android compilations where it has them.\
 * Code the build generates is not checked.
 */
@Suppress("unused", "RedundantSuppression") // named only by implementationClass in build.gradle.kts
class DetektPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("dev.detekt")
        project.extensions.configure(DetektExtension::class.java) {
            buildUponDefaultConfig.set(true)
            config.from(project.rootDir.resolve("detekt.yml"))
        }
        project.tasks.withType(Detekt::class.java).configureEach {
            exclude { it.file.invariantSeparatorsPath.contains("/build/") }
        }
        // The typed tasks cover commonMain, jvmMain and androidMain through their compilations;
        // the JS source sets, which no JVM compilation takes, are checked alone.
        val checked = Regex("detekt(Main|Test|Dev)(Jvm|Android)|detekt(Js|Web)(Main|Test)SourceSet")
        project.tasks.named("check") { dependsOn(project.tasks.matching { checked.matches(it.name) }) }
    }
}
