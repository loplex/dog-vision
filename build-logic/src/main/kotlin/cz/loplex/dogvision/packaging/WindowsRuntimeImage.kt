@file:Suppress("UnstableApiUsage")

package cz.loplex.dogvision.packaging

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RelativePath
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec

/** Temurin's feature release the packages' runtimes are of, temurin in libs.versions.toml. */
private fun Project.temurinFeature(): Int = libs().findVersion("temurin").get().requiredVersion.toInt()

/**
 * The JDK the packages' runtimes are linked with and jpackage runs from on Linux:
 * Temurin, of [temurinFeature], which jlink asks the jmods it links for Windows to be of.
 * Temurin brings its own libjpeg, giflib, libpng, lcms2, HarfBuzz and FreeType,
 * where a distribution's OpenJDK, Ubuntu's among them, links the system's,
 * and a tar.gz would then need that distribution's.\
 * Gradle downloads it where this machine has none.
 */
fun Project.packagingJdk(): Provider<JavaLauncher> =
    extensions.getByType(JavaToolchainService::class.java).launcherFor {
        languageVersion.set(JavaLanguageVersion.of(temurinFeature()))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }

/**
 * Temurin's release [packagingJdk] is, such as 25.0.4.1+1, as its release file names it:
 * the jmods for Windows are of it exactly,
 * so that every package's runtime is the same, and moves with the JDK the build packages with.
 */
fun Project.temurinRelease(): Provider<String> = packagingJdk().map { jdk ->
    val release = jdk.metadata.installationPath.file("release").asFile
    val implementor = release.readLines().firstOrNull { it.startsWith("IMPLEMENTOR_VERSION=") }
    checkNotNull(implementor?.substringAfter("\"Temurin-", "")?.removeSuffix("\"")?.ifEmpty { null }) {
        "$release names no Temurin release: $implementor"
    }
}

/**
 * Registers windowsRuntime, which links in build/windows/runtime [what],
 * the runtime for Windows on x86-64 that the scripts in tools hand jpackage:
 * [modules] and those [moduleLists] name alone, with [packagingJdk]'s jlink,
 * from Temurin's jmods for Windows of [temurinRelease], which windowsJmods unpacks into build/windows/jmods.
 * So it is the same runtime on Windows and on Linux, where jpackage runs under Wine, whatever JDK either runs on.\
 * [name] is the image's, as windowsAppImage takes it:
 * with Cli, windowsCliRuntime links build/windows/cli/runtime, from the same jmods.
 */
fun Project.windowsRuntimeImage(
    what: String,
    modules: List<String>,
    moduleLists: FileCollection = files(),
    name: String = "",
): TaskProvider<RuntimeImage> {
    val jmods = if ("windowsJmods" in tasks.names) tasks.named("windowsJmods", Sync::class.java) else windowsJmods()
    val runtime = tasks.register("windows${name}Runtime", RuntimeImage::class.java) {
        description = "Links build/${windowsFolder(name)}/runtime, $what, from Temurin's jmods for Windows."
        group = "distribution"
        jdkHome.set(packagingJdk().map { it.metadata.installationPath.asFile.path })
        this.jmods.set(layout.dir(jmods.map { it.destinationDir }))
        this.modules.set(modules)
        this.moduleLists.from(moduleLists)
        destination.set(layout.buildDirectory.dir("${windowsFolder(name)}/runtime"))
    }
    return runtime
}

/** Registers windowsJmods, which unpacks Temurin's jmods for Windows of [temurinRelease] into build/windows/jmods. */
private fun Project.windowsJmods(): TaskProvider<Sync> {
    val feature = temurinFeature()
    val jmodsScope = configurations.dependencyScope("windowsJmods")
    val jmodsZip = configurations.resolvable("windowsJmodsZip") { extendsFrom(jmodsScope.get()) }
    dependencies.addProvider<String, ExternalModuleDependency>(
        jmodsScope.name,
        temurinRelease().map { "net.adoptium:temurin$feature-binaries:$it" },
    ) {
        // Adoptium names the zip after the release, with an underscore for its plus.
        val release = checkNotNull(version)
        artifact {
            name = "OpenJDK${feature}U-jmods_x64_windows_hotspot_${release.replace('+', '_')}"
            type = "zip"
        }
    }

    // The jmods alone, out of the folder the zip holds them in, as jlink's module path takes a folder of them.
    return tasks.register("windowsJmods", Sync::class.java) {
        description = "Unpacks Temurin's jmods for Windows into build/windows/jmods."
        into(layout.buildDirectory.dir("windows/jmods"))
        from(jmodsZip.map { zips -> zips.map { zipTree(it) } }) { include("*/*.jmod") }
        eachFile { relativePath = RelativePath(true, name) }
        includeEmptyDirs = false
    }
}
