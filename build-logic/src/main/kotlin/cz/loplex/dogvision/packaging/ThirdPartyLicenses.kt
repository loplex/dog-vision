package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import java.io.File

/**
 * Writes what an artifact holds that is not this project's own, and under which licences:
 * [notices], the text a package, a zip or a JAR carries beside LICENSE;
 * [copyright], a deb's copyright file in DEP-5; and [spdx], the SPDX expression of the whole, an rpm's License.\
 * The libraries come from [jars] and their [poms],
 * what their natives hold from [EMBEDDED_PARTS], and the rest from [extraParts].
 */
abstract class ThirdPartyLicenses : DefaultTask() {
    /** Each JAR, as "group:module:version|its file's name|the name it is installed under", empty where it is not. */
    @get:Input
    abstract val jars: ListProperty<String>

    /** Each POM of [jars]' modules, as "group:module:version|its path". */
    @get:Input
    abstract val pomPaths: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val poms: ConfigurableFileCollection

    /** Parts other tasks of this type have listed, in their [parts], as an MSI takes those of each launcher. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val includedParts: ConfigurableFileCollection

    /** What no POM describes, such as the Kotlin/JS standard library in a web page. */
    @get:Input
    abstract val extraParts: ListProperty<ThirdPartyPart>

    /** packaging/licenses, the licences' texts. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val texts: DirectoryProperty

    /** What the notices call the artifact in their first line. */
    @get:Input
    abstract val artifactName: Property<String>

    /** The years of the project's own copyright, as "2026" or "2026-2027". */
    @get:Input
    abstract val ownYears: Property<String>

    /** Paragraphs the notices add after the list, about what keeps notices of its own. */
    @get:Input
    abstract val notes: ListProperty<String>

    @get:OutputFile
    abstract val notices: RegularFileProperty

    @get:OutputFile
    abstract val copyright: RegularFileProperty

    @get:OutputFile
    abstract val spdx: RegularFileProperty

    /** Every part, for another task of this type to include, as [writeParts] writes them. */
    @get:OutputFile
    abstract val parts: RegularFileProperty

    init {
        extraParts.convention(emptyList())
        notes.convention(emptyList())
        jars.convention(emptyList())
        pomPaths.convention(emptyList())
    }

    @TaskAction
    fun write() {
        val pomOf = pomPaths.get().associate { it.substringBefore('|') to File(it.substringAfter('|')) }
        // Each module's JARs as pairs of the file's name and the name it is installed under, empty where it is not.
        val modules = jars.get().map { it.split('|') }.groupBy({ it[0] }, { it[1] to it[2] })
        val parts = mergeParts(
            modules.flatMap { (id, files) ->
                val (group, module, version) = id.split(':')
                val embedded = files.flatMap { (file, _) ->
                    EMBEDDED_PARTS.filter { (pattern, _) -> pattern.matches(file) }.flatMap { it.second }
                }
                val installed = files.map { it.second }.filter { it.isNotEmpty() } + embedded.flatMap { it.files }
                val pom = checkNotNull(pomOf[id]) { "No POM of $id was resolved" }
                listOf(pomPart(group, module, version, pom, installed.distinct())) + embedded
            } + includedParts.files.sortedBy { it.path }.flatMap { readParts(it.readText()) } + extraParts.get(),
        )
        val texts = texts.get().asFile
        notices.get().asFile.writeText(thirdPartyNotices(artifactName.get(), OWN_SPDX, parts, texts, notes.get()))
        copyright.get().asFile.writeText(
            dep5Copyright(
                "Dog Vision",
                "$VENDOR <$CONTACT>",
                HOMEPAGE,
                "${ownYears.get()} $VENDOR",
                OWN_DEBIAN,
                parts,
                texts,
            ),
        )
        spdx.get().asFile.writeText(spdxExpression(OWN_SPDX, parts))
        this.parts.get().asFile.writeText(writeParts(parts))
    }
}

/** The name of the notices, in a package, a zip or a JAR. */
const val THIRD_PARTY = "THIRD-PARTY-LICENSES.txt"

/** The project's own licence, as SPDX names it and as Debian's common-licenses does. */
const val OWN_SPDX = "GPL-3.0-or-later"
private const val OWN_DEBIAN = "GPL-3+"

/**
 * Registers [name], a [ThirdPartyLicenses] of the libraries [jars] resolve
 * but those [leftOut] resolves at the same version, which another package holds, with the POM of each.\
 * The JARs' names are those they are installed under,
 * where [installedJarNames] renames them in a configuration's folder, as the copyright matches files by name;
 * where [unpacksNatives], a JAR of natives alone is not installed but its natives are, as a Linux package has them.\
 * Project modules are this project's own, and left out: so the lists are read without building them.
 */
fun Project.thirdPartyLicenses(
    name: String,
    jars: List<NamedDomainObjectProvider<out Configuration>>,
    leftOut: List<NamedDomainObjectProvider<out Configuration>> = emptyList(),
    unpacksNatives: Boolean = false,
    configure: ThirdPartyLicenses.() -> Unit = {},
): TaskProvider<ThirdPartyLicenses> {
    val none: Provider<List<String>> = provider { emptyList() }
    // "group:module:version|file name|installed name" of each library's JAR, each configuration renaming in its own.
    val listed = jars.fold(none) { all, configuration ->
        all.zip(configuration.flatMap { libraries(it) }) { list, artifacts ->
            val renamed = installedJarNames(artifacts)
            list + artifacts.map { artifact ->
                val file = artifact.file
                val installed = if (unpacksNatives && nativesOnly(file)) "" else renamed[file.path] ?: file.name
                "${artifact.id.componentIdentifier.displayName}|${file.name}|$installed"
            }
        }
    }
    val shared = leftOut.fold(provider { emptySet<String>() }) { all, configuration ->
        all.zip(configuration.flatMap { libraries(it) }) { set, artifacts ->
            set + artifacts.map { it.id.componentIdentifier.displayName }
        }
    }
    val kept = listed.zip(shared) { list, out -> list.filter { it.substringBefore('|') !in out }.distinct() }
    // Each module's POM at the version its JAR is of:
    // a query, as a configuration would keep one version of a module that two packages hold at two,
    // as dog-vision holds org.jetbrains:annotations.
    val handler = dependencies
    val pomPaths = kept.map { list ->
        list.map { it.substringBefore('|') }.distinct().map { id ->
            val (group, module, version) = id.split(':')
            val result = handler.createArtifactResolutionQuery().forModule(group, module, version)
                .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
            val pom = result.resolvedComponents.flatMap { it.getArtifacts(MavenPomArtifact::class.java) }
                .filterIsInstance<ResolvedArtifactResult>().singleOrNull()
            "$id|${checkNotNull(pom) { "The POM of $id cannot be resolved" }.file.path}"
        }
    }
    // From the year of the first commit to that of the last, as the copyright notice of a native package states them.
    val copyrightYears = providers.exec { commandLine("git", "log", "--format=%ad", "--date=format:%Y") }
        .standardOutput.asText.map { log ->
            val years = log.lines().filter { it.isNotBlank() }.map { it.trim() }
            listOf(years.min(), years.max()).distinct().joinToString("-")
        }
    return tasks.register(name, ThirdPartyLicenses::class.java) {
        description = "Writes build/third-party/$name: what the artifact holds that is not this project's own."
        this.jars.set(kept)
        this.pomPaths.set(pomPaths)
        poms.from(pomPaths.map { list -> list.map { it.substringAfter('|') } })
        texts.set(rootProject.layout.projectDirectory.dir("packaging/licenses"))
        ownYears.set(copyrightYears)
        val folder = layout.buildDirectory.dir("third-party/$name")
        notices.set(folder.map { it.file(THIRD_PARTY) })
        copyright.set(folder.map { it.file("copyright") })
        spdx.set(folder.map { it.file("license.spdx") })
        parts.set(folder.map { it.file("parts.licences") })
        configure()
    }
}

/** The JARs of [configuration] that are libraries, not this project's modules, which need no build to be listed. */
private fun libraries(configuration: Configuration) = configuration.incoming.artifactView {
    componentFilter { it is ModuleComponentIdentifier }
}.artifacts.resolvedArtifacts
