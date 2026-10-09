@file:Suppress("UnstableApiUsage")

package cz.loplex.dogvision.packaging.windows

import cz.loplex.dogvision.packaging.jvm.installedJarNames
import cz.loplex.dogvision.packaging.licenses.thirdPartyLicenses
import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Writes in [destination] what a launcher of a Windows app image is to jpackage, named [launcherName]:
 *
 * - jars: [ownJar] and every JAR of [classpath],
 *   each under the name the app image holds it by, its own or the one [jarNames] gives it.\
 *   A JAR that holds a folder of [leftOut] is packed anew without it; every other is copied as it is.
 * - [launcherName].properties, in the format of jpackage's --add-launcher:
 *   [ownJar]'s name as its main-jar, [mainClass], [javaOptions], and whether it runs in a console.\
 *   java-options is there when empty too, as jpackage would otherwise give the launcher the main launcher's.
 * - [launcherName].classpath, the names of the JARs, one a line, in the order of the launcher's classpath:
 *   [ownJar] first, then [classpath]'s.
 * - [launcherName].modules, the modules the launcher's runtime needs, one a line.
 */
abstract class WindowsLauncherFiles : DefaultTask() {
    @get:Input
    abstract val launcherName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val ownJar: RegularFileProperty

    /** The JARs the launcher runs on besides [ownJar], in their order. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val classpath: ConfigurableFileCollection

    /** The names of the JARs of [classpath] whose own will not do, keyed by the JAR's path, as [installedJarNames]. */
    @get:Internal
    abstract val jarNames: MapProperty<String, String>

    /** The names of [classpath]'s JARs in [destination], in order, which is what [jarNames] decides of them. */
    @get:Input
    val installedNames: List<String>
        get() = classpath.files.map { jarNames.get()[it.path] ?: it.name }

    /** Folders of the JARs, as nucleus/native/win32-aarch64, which no file of the launcher's is in. */
    @get:Input
    abstract val leftOut: ListProperty<String>

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val javaOptions: ListProperty<String>

    @get:Input
    abstract val console: Property<Boolean>

    @get:Input
    abstract val runtimeModules: ListProperty<String>

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun write() {
        // jpackage takes java-options for words split at spaces.
        check(javaOptions.get().none { option -> option.any(Char::isWhitespace) }) {
            "A Java option holds a space: ${javaOptions.get()}"
        }
        val out = destination.get().asFile
        out.deleteRecursively()
        val jarFolder = File(out, "jars").apply { mkdirs() }
        val own = ownJar.get().asFile
        val jars = listOf(own to own.name) + classpath.files.zip(installedNames)
        val names = jars.map { (_, name) -> name }
        check(names.toSet().size == names.size) { "Two of $names share a name" }
        val folders = leftOut.get().map { it.trimEnd('/') + "/" }
        // A folder no JAR holds is a name gone stale, which would leave out nothing.
        for (folder in folders) check(jars.any { (jar, _) -> holds(jar, folder) }) { "No JAR holds $folder" }
        for ((jar, name) in jars) copy(jar, File(jarFolder, name), folders)

        val properties = listOf(
            "main-jar" to own.name,
            "main-class" to mainClass.get(),
            "java-options" to javaOptions.get().joinToString(" "),
            "win-console" to console.get().toString(),
        )
        for ((key, value) in properties) {
            // The values are written as they are: none of the project's needs a properties file's escapes.
            check(value.none { it == '\\' || it == '\n' || it == '\r' }) { "$key=$value needs escaping" }
        }
        File(out, "${launcherName.get()}.properties")
            .writeText(properties.joinToString("") { (key, value) -> "$key=$value\n" }, Charsets.UTF_8)
        File(out, "${launcherName.get()}.classpath").writeText(names.joinToString("") { "$it\n" }, Charsets.UTF_8)
        File(out, "${launcherName.get()}.modules").writeText(runtimeModules.get().joinToString("") { "$it\n" })
    }

    /**
     * Copies [jar] to [target], without what is in [folders].\
     * A JAR that holds none of them it copies as it is.\
     * It fails on a signed JAR that holds one, whose signature would not hold once packed anew.
     */
    private fun copy(jar: File, target: File, folders: List<String>) {
        if (folders.none { holds(jar, it) }) {
            jar.copyTo(target)
            return
        }
        ZipFile(jar).use { zip ->
            check(zip.entries().asSequence().none { SIGNATURE.matches(it.name) }) { "$jar is signed" }
            ZipOutputStream(target.outputStream().buffered()).use { packed ->
                for (entry in zip.entries()) {
                    if (folders.any { entry.name.startsWith(it) }) continue
                    // The entry as it is, but for its packed size, which packing it anew decides.
                    packed.putNextEntry(ZipEntry(entry).apply { if (method == ZipEntry.DEFLATED) compressedSize = -1 })
                    zip.getInputStream(entry).use { it.copyTo(packed) }
                    packed.closeEntry()
                }
            }
        }
    }

    private fun holds(jar: File, folder: String): Boolean =
        ZipFile(jar).use { zip -> zip.entries().asSequence().any { it.name.startsWith(folder) } }

    private companion object {
        val SIGNATURE = Regex("META-INF/[^/]+\\.(SF|DSA|RSA|EC)")
    }
}

/** The usage of the variant that a module hands :packaging its Windows launcher by. */
const val WINDOWS_LAUNCHER_USAGE = "dog-vision-windows-launcher"

/**
 * Registers windowsLauncher, which writes in build/windows/launcher what the launcher [name] of a Windows app image is,
 * as [WindowsLauncherFiles] says, and the variant of the module that hands :packaging those files:
 * the launcher starts [mainClass] from [ownJar], on [classpath] after it,
 * with [javaOptions], in a console where [console], on a runtime with [runtimeModules].\
 * No file of the launcher's is in a folder of the JARs that [leftOut] names.\
 * The variant hands over too what [classpath] holds that is not this project's own,
 * [name].licences, for the app image's licences.
 */
fun Project.windowsLauncher(
    name: String,
    ownJar: Provider<RegularFile>,
    classpath: NamedDomainObjectProvider<Configuration>,
    mainClass: String,
    javaOptions: List<String> = emptyList(),
    console: Boolean = false,
    runtimeModules: List<String>,
    leftOut: List<String> = emptyList(),
): TaskProvider<WindowsLauncherFiles> {
    val files = tasks.register("windowsLauncher", WindowsLauncherFiles::class.java) {
        description = "Writes build/windows/launcher, what $name is to jpackage in an app image for Windows."
        group = "distribution"
        launcherName.set(name)
        this.ownJar.set(ownJar)
        this.classpath.from(classpath)
        jarNames.set(classpath.flatMap { it.incoming.artifacts.resolvedArtifacts }.map(::installedJarNames))
        this.leftOut.set(leftOut)
        this.mainClass.set(mainClass)
        this.javaOptions.set(javaOptions)
        this.console.set(console)
        this.runtimeModules.set(runtimeModules)
        destination.set(layout.buildDirectory.dir("windows/launcher"))
    }
    val licences = thirdPartyLicenses("windowsLauncherLicences", listOf(classpath)) {
        artifactName.set("The launcher $name")
        parts.set(layout.buildDirectory.file("third-party/windowsLauncherLicences/$name.licences"))
    }
    configurations.consumable("windowsLauncherElements") {
        attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, WINDOWS_LAUNCHER_USAGE)) }
        outgoing.artifact(files.flatMap { it.destination.dir("jars") })
        for (suffix in listOf("properties", "classpath", "modules")) {
            outgoing.artifact(files.flatMap { it.destination.file("$name.$suffix") })
        }
        outgoing.artifact(licences.flatMap { it.parts })
    }
    return files
}
