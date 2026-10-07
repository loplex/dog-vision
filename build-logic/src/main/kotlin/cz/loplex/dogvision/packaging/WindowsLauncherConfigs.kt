package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Writes in [destination] the .cfg of each launcher in the app folder of [image],
 * a Windows app image as jpackage made it of [WindowsJpackageFiles],
 * with the classpath the launcher's .cfg among [classpaths] names, in its order:
 * jpackage puts every JAR of its input on each launcher's, the launcher's own first.\
 * The scripts in tools copy them over jpackage's.\
 * Beside them [license] and [notices], which the scripts copy into the image's folder.
 */
abstract class WindowsLauncherConfigs : DefaultTask() {
    @get:Internal
    abstract val image: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    val configs: FileTree
        get() = image.asFileTree.matching { include("app/*.cfg") }

    /** Each launcher's .classpath, as [WindowsLauncherFiles] writes it. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val classpaths: ConfigurableFileCollection

    /** The project's licence, LICENSE. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val license: RegularFileProperty

    /** What the image holds that is not this project's own, as [ThirdPartyLicenses] writes it. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notices: RegularFileProperty

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun write() {
        val out = destination.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val app = image.get().dir("app").asFile
        val configs = configs.files
        check(configs.isNotEmpty()) { "$app holds no launcher's .cfg" }
        val classpaths = classpaths.files.associateBy { it.name }
        val onClasspaths = mutableSetOf<String>()
        for (config in configs) {
            val names = checkNotNull(classpaths["${config.nameWithoutExtension}.classpath"]) {
                "$config's launcher has no .classpath"
            }.readLines(Charsets.UTF_8)
            check(names.isNotEmpty() && names.all { File(app, it).isFile }) { "$app does not hold every JAR of $names" }
            onClasspaths += names
            // The lines with their ends, which jpackage writes as the system it runs on does.
            val lines = Regex("(?<=\n)").split(config.readText(Charsets.UTF_8)).filter(String::isNotEmpty)
            val first = lines.indexOfFirst { it.startsWith(LAUNCHER_CLASSPATH) }
            check(first >= 0) { "$config has no $LAUNCHER_CLASSPATH" }
            val end = lines[first].substring(lines[first].trimEnd().length)
            val classpath = names.map { $$"$$LAUNCHER_CLASSPATH$APPDIR\\$$it$$end" }
            val others = lines.filterNot { it.startsWith(LAUNCHER_CLASSPATH) }
            File(out, config.name).writeText(
                (others.take(first) + classpath + others.drop(first)).joinToString(""),
                Charsets.UTF_8,
            )
        }
        val jars = app.listFiles().orEmpty().filter { it.name.endsWith(".jar") }.map { it.name }.toSet()
        check(onClasspaths == jars) { "JARs of $app on no launcher's classpath: ${jars - onClasspaths}" }
        license.get().asFile.copyTo(File(out, "LICENSE"))
        notices.get().asFile.copyTo(File(out, THIRD_PARTY))
    }
}

/** The key of a line of a launcher's .cfg that names a file of its classpath, which jpackage writes one a line. */
internal const val LAUNCHER_CLASSPATH = "app.classpath="
