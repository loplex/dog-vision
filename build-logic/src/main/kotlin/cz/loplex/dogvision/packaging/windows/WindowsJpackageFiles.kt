package cz.loplex.dogvision.packaging.windows

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.util.Properties

/**
 * Writes in [destination] what jpackage makes a Windows app image of,
 * which tools/package_app_image_on_linux.sh hands it under Wine and tools/package_msi_on_windows.ps1
 * and tools/package_cli_zip_on_windows.ps1 on Windows, so that both make the same image:
 *
 * - arguments: the package's name, version, vendor, description and icon; the folder of the JARs,
 *   the main launcher's JAR, class, Java options and console, [runtime], and every other launcher.
 * - input, the folder of the launchers' JARs alone, as jpackage takes every file in its --input into the application.\
 *   A JAR on more than one launcher's classpath is there once: JARs of one name have to be the same bytes.
 *
 * The launchers are [launchers]' files as [WindowsLauncherFiles] writes them:
 * the one named [packageName] is the main launcher, and each other one an --add-launcher.\
 * jpackage puts every JAR of input on each launcher's classpath, after the launcher's own;
 * [WindowsLauncherConfigs] gives each launcher the classpath its .classpath names instead.
 *
 * The arguments file is UTF-8, each argument in double quotes,
 * in which a backslash and a quote are escaped with a backslash, as jpackage reads @files:
 * within quotes, \n, \r, \t and \f are taken for control characters, which a Windows path holds.\
 * JDK 17's jpackage reads a file in its default charset, which before JDK 18 is the system's,
 * so tools/package_app_image_on_linux.sh runs it with -J-Dfile.encoding=UTF-8.\
 * Paths in the file are Windows's, under Wine, where the build does not run on Windows,
 * as Wine sees them through its drive Z:, which it maps to the root.
 */
abstract class WindowsJpackageFiles : DefaultTask() {
    /** The name of the image's folder, and of its main launcher. */
    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val vendor: Property<String>

    @get:Input
    abstract val packageDescription: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val icon: RegularFileProperty

    /** Each launcher's properties, classpath, modules and JARs, as [WindowsLauncherFiles] says, or folders of them. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val launchers: ConfigurableFileCollection

    /** The runtime the image runs on, as windowsRuntime links it. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val runtime: DirectoryProperty

    /** Whether jpackage runs under Wine, which sees the paths through its drive Z:, rather than on Windows. */
    @get:Input
    abstract val underWine: Property<Boolean>

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun write() {
        val out = destination.get().asFile
        out.deleteRecursively()
        val input = File(out, "input").apply { mkdirs() }
        // The files, out of the folders among them, as a module hands its launcher's JARs in a folder.
        val files = launchers.asFileTree.files
        val jars = files.filter { it.name.endsWith(".jar") }.groupBy { it.name }
        for ((name, copies) in jars) {
            val differ = copies.drop(1).filter { Files.mismatch(it.toPath(), copies.first().toPath()) != -1L }
            check(differ.isEmpty()) { "The launchers' JARs named $name differ: ${listOf(copies.first()) + differ}" }
            copies.first().copyTo(File(input, name))
        }

        val properties = files.filter { it.name.endsWith(".properties") }.sortedBy { it.name }
        val classpaths = files.filter { it.name.endsWith(".classpath") }.associateBy { it.name }
        val onClasspaths = mutableSetOf<String>()
        for (file in properties) {
            val mainJar = read(file).getProperty("main-jar")
            val classpath = classpaths["${file.name.removeSuffix(".properties")}.classpath"]
            checkNotNull(classpath) { "$file has no .classpath beside it" }
            val names = classpath.readLines(Charsets.UTF_8)
            check(names.firstOrNull() == mainJar) { "$classpath does not start with $file's main-jar $mainJar" }
            check(names.all { it in jars }) { "$classpath names what is not among the launchers' JARs: $names" }
            onClasspaths += names
        }
        check(onClasspaths == jars.keys) { "JARs on no launcher's classpath: ${jars.keys - onClasspaths}" }
        val main = properties.singleOrNull { it.name == "${packageName.get()}.properties" }
        checkNotNull(main) { "No launcher is named ${packageName.get()}, the image's main launcher: $properties" }
        val mainProperties = read(main)
        val javaOptions = mainProperties.getProperty("java-options").orEmpty().split(' ').filter(String::isNotEmpty)
        val console = mainProperties.getProperty("win-console") == "true"

        writeArguments(
            File(out, "arguments"),
            listOf(
                "--name" to packageName.get(),
                "--app-version" to version.get(),
                "--vendor" to vendor.get(),
                "--description" to packageDescription.get(),
                "--icon" to path(icon.get().asFile),
                "--input" to path(input),
                "--main-jar" to mainProperties.getProperty("main-jar"),
                "--main-class" to mainProperties.getProperty("main-class"),
            ) +
                javaOptions.map { "--java-options" to it } +
                (if (console) listOf("--win-console" to null) else emptyList()) +
                listOf("--runtime-image" to path(runtime.get().asFile)) +
                (properties - main).map { launcher ->
                    "--add-launcher" to "${launcher.name.removeSuffix(".properties")}=${path(launcher)}"
                },
        )
    }

    private fun read(file: File): Properties = Properties().apply { file.reader(Charsets.UTF_8).use(::load) }

    /** [file]'s path as jpackage sees it: Windows's own, or under Wine through its drive Z:. */
    private fun path(file: File): String =
        if (underWine.get()) "Z:" + file.absolutePath.replace('/', '\\') else file.absolutePath

    /** Writes [options] into [file], each an option and its value, where it takes one. */
    private fun writeArguments(file: File, options: List<Pair<String, String?>>) {
        val arguments = options.flatMap { (option, value) -> listOfNotNull(option, value) }
        val lines = arguments.map { argument -> "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        file.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }
}
