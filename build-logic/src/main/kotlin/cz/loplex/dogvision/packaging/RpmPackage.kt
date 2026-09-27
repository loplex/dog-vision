package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

/**
 * Packs [tree], the files as they are installed from /, into an rpm with rpmbuild, as jpackage cannot: it always puts a
 * Java runtime in. The rpm is named as rpmbuild names them, name-version-release.architecture.rpm, and:
 * - it owns the folders of the tree under one named after the package, such as /usr/share/<name>, and no other, which
 *   the system's packages own;
 * - it names only what it is told to require: rpmbuild's generators, which differ from one build machine to another,
 *   do not run, and neither do the scripts that strip, compress or repack what it installs.
 */
abstract class RpmPackage : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tree: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val release: Property<String>

    /** "noarch" for a package with no natives, "x86_64" for one with x86-64's. */
    @get:Input
    abstract val architecture: Property<String>

    @get:Input
    abstract val url: Property<String>

    /** The license's SPDX identifier. */
    @get:Input
    abstract val licenseName: Property<String>

    /** The one line the package is listed with. */
    @get:Input
    abstract val summary: Property<String>

    /** What follows it, its paragraphs apart by blank lines. */
    @get:Input
    abstract val longDescription: Property<String>

    /** Each a requirement rpm reads, a rich dependency such as (A or B) among them, which rpm reads from 4.13 on. */
    @get:Input
    abstract val requires: ListProperty<String>

    @get:Input
    abstract val recommends: ListProperty<String>

    /** The license's text, which the rpm installs as %license, under /usr/share/licenses/<name>. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val license: RegularFileProperty

    @get:Internal
    abstract val destinationDirectory: DirectoryProperty

    @get:OutputFile
    val rpm: Provider<RegularFile>
        get() = destinationDirectory.file(
            packageName.zip(version) { name, version -> "$name-$version" }
                .zip(release) { nameVersion, release -> "$nameVersion-$release" }
                .zip(architecture) { nameVersionRelease, architecture -> "$nameVersionRelease.$architecture.rpm" },
        )

    @TaskAction
    fun pack() {
        val top = Files.createTempDirectory("rpm").toFile()
        try {
            val root = top.resolve("SOURCES/root")
            root.mkdirs()
            stage(tree.get().asFile, root)
            license.get().asFile.copyTo(top.resolve("SOURCES/LICENSE"))
            val entries = root.walkTopDown().drop(1).sortedBy { it.path }.mapNotNull { file ->
                val path = "/" + file.relativeTo(root).invariantSeparatorsPath
                check(path.none { it.isWhitespace() || it == '"' }) { "rpm's %files cannot take $path as it is" }
                when {
                    file.isFile -> path
                    packageName.get() in path.split('/') -> "%dir $path"
                    else -> null
                }
            }
            val spec = top.resolve("package.spec")
            spec.writeText(
                buildString {
                    appendLine("Name: ${packageName.get()}")
                    appendLine("Version: ${version.get()}")
                    appendLine("Release: ${release.get()}")
                    appendLine("Summary: ${summary.get()}")
                    appendLine("License: ${licenseName.get()}")
                    appendLine("URL: ${url.get()}")
                    appendLine("BuildArch: ${architecture.get()}")
                    appendLine("AutoReqProv: no")
                    for (requirement in requires.get()) appendLine("Requires: $requirement")
                    for (recommendation in recommends.get()) appendLine("Recommends: $recommendation")
                    appendLine("%global __os_install_post %{nil}")
                    appendLine("%global debug_package %{nil}")
                    // /usr/share/licenses/<name>, as Fedora's and openSUSE's rpm name it, where others add the version.
                    appendLine("%global _docdir_fmt %{NAME}")
                    appendLine()
                    appendLine("%description")
                    appendLine(longDescription.get().trim())
                    appendLine()
                    appendLine("%prep")
                    appendLine("cp %{_sourcedir}/LICENSE .")
                    appendLine()
                    appendLine("%install")
                    appendLine("cp -a %{_sourcedir}/root/. %{buildroot}/")
                    appendLine()
                    appendLine("%files")
                    appendLine("%license LICENSE")
                    for (entry in entries) appendLine(entry)
                },
            )
            val rpm = rpm.get().asFile
            rpm.parentFile.mkdirs()
            run(
                "rpmbuild", "-bb", spec.path,
                "--define", "_topdir $top",
                "--define", "_sourcedir ${top.resolve("SOURCES")}",
                "--define", "_rpmdir ${rpm.parent}",
                "--define", "_rpmfilename ${rpm.name}",
            )
        } finally {
            top.deleteRecursively()
        }
    }
}
