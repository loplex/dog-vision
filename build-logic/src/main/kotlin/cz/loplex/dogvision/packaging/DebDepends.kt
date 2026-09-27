package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * The Debian package of each library the app image links against, by soname, as Ubuntu 20.04, the oldest release the
 * debs are for, names them: later releases keep the names or provide them (Ubuntu 24.04's libasound2t64 provides
 * libasound2). [DebDepends] fails on a library missing here.
 */
val debianPackages = mapOf(
    "ld-linux-x86-64.so.2" to "libc6",
    "libc.so.6" to "libc6",
    "libdl.so.2" to "libc6",
    "libm.so.6" to "libc6",
    "libpthread.so.0" to "libc6",
    "librt.so.1" to "libc6",
    "libasound.so.2" to "libasound2",
    "libfontconfig.so.1" to "libfontconfig1",
    "libGL.so.1" to "libgl1",
    "libstdc++.so.6" to "libstdc++6",
    "libX11.so.6" to "libx11-6",
    "libXext.so.6" to "libxext6",
    "libXi.so.6" to "libxi6",
    "libXrender.so.1" to "libxrender1",
    "libXtst.so.6" to "libxtst6",
)

/**
 * Writes the deb's Depends from the app image alone, so that it is the same wherever the deb is built: jpackage looks
 * the libraries up in the build machine's dpkg database, and so names its release's packages, such as Ubuntu 24.04's
 * libasound2t64, which older releases lack. Each library the image's ELF files need and do not bring is named by
 * [packages], libc6 with the newest glibc version they ask for; [others] follow.
 */
abstract class DebDepends : DefaultTask() {
    @get:InputDirectory
    abstract val image: DirectoryProperty

    @get:Input
    abstract val packages: MapProperty<String, String>

    @get:Input
    abstract val others: ListProperty<String>

    @get:OutputFile
    abstract val depends: RegularFileProperty

    @TaskAction
    fun write() {
        val elfFiles = elfFiles(image.get().asFile)
        val dynamic = elfFiles.flatMap { readelf("-d", it) }
        fun tagged(tag: String) = dynamic.mapNotNull { Regex("""\($tag\).*\[(.+)]""").find(it)?.groupValues?.get(1) }
        val brought = tagged("SONAME").toSet() + elfFiles.map { it.name }
        val needed = tagged("NEEDED").toSet() - brought
        val table = packages.get()
        val unknown = needed - table.keys
        check(unknown.isEmpty()) { "debianPackages names no package for ${unknown.sorted()}" }
        val glibc = elfFiles.flatMap { readelf("-V", it) }
            .mapNotNull { Regex("""Name: GLIBC_([0-9.]+)""").find(it)?.groupValues?.get(1) }
            .maxWith(compareBy<String>({ it.split('.')[0].toInt() }, { it.split('.').getOrElse(1) { "0" }.toInt() }))
        val libraries = needed.map { table.getValue(it) }.distinct().sorted()
            .map { if (it == "libc6") "libc6 (>= $glibc)" else it }
        depends.get().asFile.writeText((libraries + others.get()).joinToString(", "))
    }

    private fun readelf(option: String, file: File): List<String> {
        val process = ProcessBuilder("readelf", option, file.path).redirectErrorStream(true)
            .apply { environment()["LC_ALL"] = "C" }.start()
        val lines = process.inputReader().readLines()
        check(process.waitFor() == 0) { "readelf $option $file failed: $lines" }
        return lines
    }
}
