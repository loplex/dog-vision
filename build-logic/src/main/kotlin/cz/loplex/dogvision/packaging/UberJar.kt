package cz.loplex.dogvision.packaging

import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCopyDetails
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * Makes this task an uber JAR named [fileName],
 * of [ownJar] and every JAR of [classpath], which `java -jar` runs alone, starting [mainClass].\
 * Each path held by more than one of them is checked rather than taken from whichever comes first:
 *
 * - Left out of every JAR: its manifest, as this one writes its own; META-INF/INDEX.LIST, the JAR index,
 *   which JDK 18 ignores and JDK 21 removed, and whose copy from LWJGL names JARs that are not beside this one;
 *   signatures, which the merged JAR breaks; module-info.class, as the JAR is no module; and [excludes].
 * - Left out of a JAR that holds no class: its .kotlin_module, which then lists no class of it.
 *   JetBrains' forwarding JARs, such as org.jetbrains.compose.runtime's runtime-desktop,
 *   hold one of the same name as the library they forward to, androidx.compose.runtime's.
 * - Any other path held twice has to be the same bytes each time, and is taken once; the task fails where it is not.
 *
 * [notices], what it holds that is not this project's own and under which licences,
 * goes in as META-INF/THIRD-PARTY-LICENSES.txt, as [uberJarLicences] writes it.
 */
fun Jar.uberJar(
    fileName: String,
    mainClass: String,
    ownJar: Provider<RegularFile>,
    classpath: Provider<out Iterable<File>>,
    notices: Provider<RegularFile>,
    excludes: List<String> = emptyList(),
) {
    archiveFileName.set(fileName)
    manifest { attributes(mapOf("Main-Class" to mainClass)) }
    // The service rather than the project, which the configuration cache cannot keep.
    val archives = project.objects.newInstance(Archives::class.java).operations
    from(ownJar.map { archives.zipTree(it) })
    from(
        classpath.map { jars ->
            jars.map { jar ->
                // The JAR is read as it is copied, not when Gradle plans the build, before a module's own JAR is built.
                archives.zipTree(jar).matching { exclude { KOTLIN_MODULE.matches(it.path) && holdsNoClass(jar) } }
            }
        },
    )
    from(notices) { into("META-INF") }
    val leftOut = UBER_JAR_EXCLUDES + excludes
    exclude(leftOut)
    // Gradle fingerprints the zip files, not what the patterns leave of them,
    // and would otherwise keep the JAR as it is when a pattern changes.
    inputs.property("excludes", leftOut)

    val duplicates = Duplicates(fileName, ownJar.map { listOf(it.asFile) }.zip(classpath) { own, jars -> own + jars })
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    doFirst { duplicates.clear() }
    eachFile { duplicates.check(this) }
}

/**
 * Registers [name], the licences of the uber JAR [fileName] of [classpath], whose notices [uberJar] takes:
 * the natives stay in their JARs, as the uber JAR holds them.
 */
fun Project.uberJarLicences(
    name: String,
    fileName: String,
    classpath: NamedDomainObjectProvider<out Configuration>,
): Provider<RegularFile> = thirdPartyLicenses(name, listOf(classpath)) { artifactName.set("The JAR $fileName") }
    .flatMap { it.notices }

/** What an uber JAR leaves out of every JAR it merges, as [uberJar] says why. */
private val UBER_JAR_EXCLUDES = listOf(
    "META-INF/MANIFEST.MF",
    "META-INF/INDEX.LIST",
    "META-INF/*.SF",
    "META-INF/*.DSA",
    "META-INF/*.RSA",
    "**/module-info.class",
)

/** A JAR's .kotlin_module, which [uberJar] leaves out of a JAR that holds no class. */
private val KOTLIN_MODULE = Regex("META-INF/[^/]+\\.kotlin_module")

/** Gradle's service that reads a zip file as a file tree. */
internal abstract class Archives @Inject constructor(val operations: ArchiveOperations)

private fun holdsNoClass(jar: File): Boolean =
    ZipFile(jar).use { zip -> zip.entries().asSequence().none { it.name.endsWith(".class") } }

/**
 * The digest of each path the uber JAR [fileName] has taken so far, of [jars].\
 * Where a path differs, the JARs that hold it are looked up then, as an entry does not say which JAR it comes from.
 */
private class Duplicates(private val fileName: String, private val jars: Provider<List<File>>) {
    private val taken = HashMap<String, String>()

    fun clear() = taken.clear()

    /** Leaves [details] out where the same bytes are taken already, and fails where other bytes are. */
    fun check(details: FileCopyDetails) {
        val digest = details.open().use { input ->
            MessageDigest.getInstance("SHA-256").apply {
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) update(buffer, 0, input.read(buffer).takeIf { it >= 0 } ?: break)
            }.digest().joinToString("") { "%02x".format(it) }
        }
        val first = taken.putIfAbsent(details.path, digest) ?: return
        if (first != digest) {
            val holders = jars.get().filter { jar -> ZipFile(jar).use { it.getEntry(details.path) != null } }
            throw GradleException(
                "${details.path} differs between the JARs merged into $fileName that hold it:\n" +
                    holders.joinToString("\n") { "  $it" },
            )
        }
        details.exclude()
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
