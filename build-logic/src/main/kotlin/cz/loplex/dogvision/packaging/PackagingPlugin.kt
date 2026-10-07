package cz.loplex.dogvision.packaging

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Applied by a module that makes Linux packages or a package for Windows:
 * it puts this package's tasks and functions on the module's build script classpath,
 * and gives each [DebPackage] and [RpmPackage] what every package of the project shares,
 * which the module's script registers with what is its own.
 */
@Suppress("unused", "RedundantSuppression") // named only by implementationClass in build.gradle.kts
class PackagingPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val appVersion = project.providers.gradleProperty("appVersion")
        val licenseText = project.rootProject.layout.projectDirectory.file("LICENSE")
        // Gradle's own folder of what a build distributes, base.distsDirectory's.
        val distributions = project.layout.buildDirectory.dir("distributions")
        // The last commit's date, as RFC 5322 has it, the changelog's.
        val lastCommit = project.providers.exec {
            commandLine("git", "log", "-1", "--format=%cd", "--date=format:%a, %d %b %Y %H:%M:%S %z")
            environment("LC_ALL", "C")
        }.standardOutput.asText.map { it.trim() }
        project.tasks.withType(DebPackage::class.java).configureEach {
            version.convention(appVersion)
            maintainer.convention("$VENDOR <$CONTACT>")
            section.convention("graphics")
            homepage.convention(HOMEPAGE)
            changelogDate.convention(lastCommit)
            destinationDirectory.convention(distributions)
        }
        project.tasks.withType(RpmPackage::class.java).configureEach {
            version.convention(appVersion)
            release.convention("1")
            url.convention(HOMEPAGE)
            license.convention(licenseText)
            destinationDirectory.convention(distributions)
        }
    }
}

/** Who makes the packages, their vendor and maintainer. */
internal const val VENDOR = "Martin Lopatář"

/** Where the vendor is reached. */
internal const val CONTACT = "lopin.git@loplex.cz"

/** The project's home, which the packages name. */
internal const val HOMEPAGE = "https://github.com/loplex/dog-vision"
