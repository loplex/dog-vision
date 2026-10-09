package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
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
import java.security.MessageDigest

/**
 * Packs [tree], the files as they are installed from /, into a deb with dpkg-deb, as jpackage cannot:
 * it always puts a Java runtime in.\
 * The deb is named as Debian names them, name_version_architecture.deb, owned by root,
 * and packed with xz, which every dpkg reads,
 * where the build machine's dpkg-deb may choose zstd, which Debian's reads only from Debian 12 on.
 */
abstract class DebPackage : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tree: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val version: Property<String>

    /** "all" for a package with no natives, "amd64" for one with x86-64's. */
    @get:Input
    abstract val architecture: Property<String>

    @get:Input
    abstract val maintainer: Property<String>

    @get:Input
    abstract val section: Property<String>

    @get:Input
    abstract val homepage: Property<String>

    /** The one line the package is listed with. */
    @get:Input
    abstract val summary: Property<String>

    /** What follows it, its paragraphs apart by blank lines. */
    @get:Input
    abstract val longDescription: Property<String>

    @get:Input
    abstract val depends: ListProperty<String>

    @get:Input
    abstract val recommends: ListProperty<String>

    /** The packages that cannot be installed beside this one, which apt removes to install it. */
    @get:Input
    abstract val conflicts: ListProperty<String>

    /** The packages whose files this one takes over, as Debian Policy 7.6 has it, where they install the same ones. */
    @get:Input
    abstract val replaces: ListProperty<String>

    /**
     * The copyright file, which the deb installs as /usr/share/doc/<name>/copyright, as Debian's packages do:
     * the one [ThirdPartyLicenses] writes in DEP-5, of the project's own licence and of what the package holds besides.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val license: RegularFileProperty

    /**
     * The date of the version, as the changelog has it, RFC 5322's:
     * the last commit's, so that a deb built again of the same commit is the same.
     */
    @get:Input
    abstract val changelogDate: Property<String>

    /** The symbolic links the deb installs besides [tree], each path as installed from / to its target. */
    @get:Input
    abstract val links: MapProperty<String, String>

    @get:Internal
    abstract val destinationDirectory: DirectoryProperty

    init {
        conflicts.convention(emptyList())
        replaces.convention(emptyList())
        links.convention(emptyMap())
    }

    @get:OutputFile
    val deb: Provider<RegularFile>
        get() = destinationDirectory.file(
            packageName.zip(version) { name, version -> "${name}_$version" }
                .zip(architecture) { nameVersion, architecture -> "${nameVersion}_$architecture.deb" },
        )

    @TaskAction
    fun pack() {
        val root = Files.createTempDirectory("deb").toFile()
        try {
            stage(tree.get().asFile, root)
            place(license.get().asFile.readBytes(), root, "usr/share/doc/${packageName.get()}/copyright")
            // A native package's changelog, as its version has no Debian revision: one entry, of this version,
            // compressed as Debian Policy 12.7 asks, gzip -9n, which leaves no name and no time in it.
            val changelog = "${packageName.get()} (${version.get()}) unstable; urgency=medium\n\n" +
                "  * Version ${version.get()}.\n\n -- ${maintainer.get()}  ${changelogDate.get()}\n"
            place(gzip(changelog.toByteArray()), root, "usr/share/doc/${packageName.get()}/changelog.gz")
            for ((path, target) in links.get()) link(root, path, target)
            // The regular files: a link has no sum of its own in md5sums, and takes no space.
            val files = root.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.toList()
            val installedSize = files.sumOf { (it.length() + 1023) / 1024 }
            val control = buildString {
                appendLine("Package: ${packageName.get()}")
                appendLine("Version: ${version.get()}")
                appendLine("Architecture: ${architecture.get()}")
                appendLine("Maintainer: ${maintainer.get()}")
                appendLine("Installed-Size: $installedSize")
                if (depends.get().isNotEmpty()) appendLine("Depends: ${depends.get().joinToString(", ")}")
                if (recommends.get().isNotEmpty()) appendLine("Recommends: ${recommends.get().joinToString(", ")}")
                if (conflicts.get().isNotEmpty()) appendLine("Conflicts: ${conflicts.get().joinToString(", ")}")
                if (replaces.get().isNotEmpty()) appendLine("Replaces: ${replaces.get().joinToString(", ")}")
                appendLine("Section: ${section.get()}")
                appendLine("Priority: optional")
                appendLine("Homepage: ${homepage.get()}")
                appendLine("Description: ${summary.get()}")
                for (line in longDescription.get().trim().lines()) appendLine(if (line.isBlank()) " ." else " $line")
            }
            place(control.toByteArray(), root, "DEBIAN/control")
            // What dpkg --verify checks the installed files against.
            val md5sums = files.sortedBy { it.relativeTo(root).path }.joinToString("") { file ->
                val digest = MessageDigest.getInstance("MD5").digest(file.readBytes())
                "${digest.joinToString("") { "%02x".format(it) }}  ${file.relativeTo(root).path}\n"
            }
            place(md5sums.toByteArray(), root, "DEBIAN/md5sums")
            deb.get().asFile.parentFile.mkdirs()
            run("dpkg-deb", "--root-owner-group", "-Zxz", "-b", root.path, deb.get().asFile.path)
        } finally {
            root.deleteRecursively()
        }
    }
}
