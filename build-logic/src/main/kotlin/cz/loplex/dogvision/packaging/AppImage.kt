package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Makes jpackage's app image of [jars] in [destination]/[imageName]: a native launcher that starts [mainClass], the
 * JARs, and a runtime of its own that jlink links from [jdkHome] with [modules] alone, as Compose's createDistributable
 * makes the Compose window's, for a module Compose's plugin is not applied to. Each of [launchers] is another launcher,
 * from its properties file.
 */
abstract class AppImage : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val files: FileSystemOperations

    /** The JDK jpackage runs from, and whose modules the runtime is linked from. */
    @get:Input
    abstract val jdkHome: Property<String>

    @get:Classpath
    abstract val jars: ConfigurableFileCollection

    /** The name of the JAR among [jars] that holds [mainClass]. */
    @get:Input
    abstract val mainJar: Property<String>

    @get:Input
    abstract val mainClass: Property<String>

    /** The app image's name, its folder's and its launcher's. */
    @get:Input
    abstract val imageName: Property<String>

    @get:Input
    abstract val appVersion: Property<String>

    @get:Input
    abstract val modules: ListProperty<String>

    @get:Input
    abstract val javaOptions: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val icon: RegularFileProperty

    /** Further launchers: each one's name, and the properties file jpackage's --add-launcher reads. */
    @get:Internal
    abstract val launchers: MapProperty<String, File>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val launcherFiles get() = launchers.map { it.values }

    @get:Input
    val launcherNames get() = launchers.map { it.keys.toList() }

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    /** Where the JARs are gathered for jpackage's --input, which takes every file in one folder. */
    @get:Internal
    abstract val input: DirectoryProperty

    @TaskAction
    fun make() {
        val input = input.get().asFile
        files.sync {
            from(jars)
            into(input)
        }
        check(input.resolve(mainJar.get()).isFile) { "${mainJar.get()} is not among the JARs" }
        // jpackage refuses a destination that holds the image already.
        files.delete { delete(destination) }
        val command = mutableListOf(
            "${jdkHome.get()}/bin/jpackage",
            "--type",
            "app-image",
            "--input",
            input.path,
            "--main-jar",
            mainJar.get(),
            "--main-class",
            mainClass.get(),
            "--name",
            imageName.get(),
            "--app-version",
            appVersion.get(),
            "--icon",
            icon.get().asFile.path,
            "--add-modules",
            modules.get().joinToString(","),
            "--dest",
            destination.get().asFile.path,
        )
        for (option in javaOptions.get()) command += listOf("--java-options", option)
        for ((launcher, properties) in launchers.get()) command += listOf("--add-launcher", "$launcher=$properties")
        exec.exec { commandLine(command) }
    }
}
