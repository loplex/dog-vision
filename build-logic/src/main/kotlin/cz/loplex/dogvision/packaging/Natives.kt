package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTreeElement
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

/**
 * Whether [jar] holds native libraries and nothing else but its manifest and their checksums, as LWJGL's
 * natives-linux JARs and skiko's runtime do: a package installs the libraries apart, where the JVM loads them, and
 * leaves the JAR off the classpath.
 */
fun nativesOnly(jar: File): Boolean = ZipFile(jar).use { zip ->
    val files = zip.entries().asSequence().filterNot { it.isDirectory || it.name.startsWith("META-INF/") }.toList()
    files.isNotEmpty() && files.all { NATIVE.containsMatchIn(it.name) }
}

/** Matches a JAR that [nativesOnly] holds natives only, for a copy to leave it out. */
object NativesOnly : Spec<FileTreeElement> {
    override fun isSatisfiedBy(element: FileTreeElement) = nativesOnly(element.file)
}

/**
 * Unpacks every Linux native library, *.so, out of [jars] into [natives], side by side, where skiko and LWJGL load
 * them from when skiko.library.path and org.lwjgl.librarypath name the folder. They otherwise unpack them at run
 * time, into the user's home or /tmp. Fails on two libraries of one name.
 */
abstract class UnpackNatives : DefaultTask() {
    @get:Classpath
    abstract val jars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val natives: DirectoryProperty

    @TaskAction
    fun unpack() {
        val folder = natives.get().asFile
        folder.deleteRecursively()
        folder.mkdirs()
        for (jar in jars.files) {
            ZipFile(jar).use { zip ->
                for (entry in zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".so") }) {
                    val target = folder.resolve(entry.name.substringAfterLast('/'))
                    check(!target.exists()) { "Two native libraries named ${target.name}, one of them in $jar" }
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }
}

private val NATIVE = Regex("""\.(so|dll|dylib)(\.(sha1|sha256|git))?$""")
