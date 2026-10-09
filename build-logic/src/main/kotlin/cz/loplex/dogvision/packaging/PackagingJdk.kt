package cz.loplex.dogvision.packaging

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec

/** Temurin's feature release the packages' runtimes are of, temurin in libs.versions.toml. */
internal fun Project.temurinFeature(): Int = libs().findVersion("temurin").get().requiredVersion.toInt()

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
