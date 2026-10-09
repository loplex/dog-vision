package cz.loplex.dogvision.packaging.linux

import cz.loplex.dogvision.packaging.gzip
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Compresses [page], a manual page's roff source, into [compressed], as a package installs it in /usr/share/man:
 * by gzip -9n, as Debian's policy asks, so that the file holds no name and no time and is the same at every build.
 */
abstract class ManPage : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val page: RegularFileProperty

    @get:OutputFile
    abstract val compressed: RegularFileProperty

    @TaskAction
    fun compress() {
        val output = compressed.get().asFile
        output.parentFile.mkdirs()
        output.writeBytes(gzip(page.get().asFile.readBytes()))
    }
}
