package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Lists the libraries an app image's runtime and natives link against, as rpm's own generator, elfdeps, names them for
 * an rpm's requirements (libX11.so.6()(64bit) and the like), less those the image brings itself. jpackage asks the
 * build machine's rpm database which packages own them, which off an rpm-based system finds none.
 */
abstract class RpmLibraryRequires : DefaultTask() {
    @get:InputDirectory
    abstract val image: DirectoryProperty

    /** The requirements, joined by commas as jpackage's --linux-package-deps takes them. */
    @get:OutputFile
    abstract val requires: RegularFileProperty

    @TaskAction
    fun list() {
        // elfdeps fails on a last line without its line feed
        val elfFiles = elfFiles(image.get().asFile).joinToString("") { it.path + "\n" }
        val provided = elfdeps("--provides", elfFiles).map { it.substringBefore('(') }.toSet()
        val required = elfdeps("--requires", elfFiles).filter { it.substringBefore('(') !in provided }
        requires.get().asFile.writeText(required.distinct().sorted().joinToString(","))
    }

    private fun elfdeps(mode: String, files: String): List<String> {
        val rpmConfigDir = ProcessBuilder("rpm", "--eval", "%{_rpmconfigdir}").start().inputReader().readText().trim()
        val process = ProcessBuilder("$rpmConfigDir/elfdeps", mode).redirectErrorStream(true).start()
        process.outputWriter().use { it.write(files) }
        val lines = process.inputReader().readLines()
        check(process.waitFor() == 0) { "elfdeps $mode failed: $lines" }
        return lines
    }
}
