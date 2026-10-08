package cz.loplex.dogvision

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * Applied by every module: its Kotlin, its build script's included, is held to .editorconfig by ktlint in `check`,
 * of the version ktlint-cli in libs.versions.toml.
 */
@Suppress("unused", "RedundantSuppression") // named only by implementationClass in build.gradle.kts
class KtlintPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jlleitschuh.gradle.ktlint")
        val libs = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        project.extensions.configure(KtlintExtension::class.java) {
            version.set(libs.findVersion("ktlint-cli").get().requiredVersion)
        }
    }
}
