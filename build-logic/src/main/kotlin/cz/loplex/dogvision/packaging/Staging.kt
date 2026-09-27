package cz.loplex.dogvision.packaging

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Copies [tree] into [into] with the modes a package installs: folders rwxr-xr-x, files rwxr-xr-x where they are
 * executable and rw-r--r-- otherwise, whatever the build's umask and the modes the files had in Gradle's caches.
 */
internal fun stage(tree: File, into: File) {
    for (source in tree.walkTopDown()) {
        val target = into.resolve(source.relativeTo(tree))
        if (source.isDirectory) target.mkdirs() else source.copyTo(target)
        val mode = if (source.isDirectory || source.canExecute()) "rwxr-xr-x" else "rw-r--r--"
        Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString(mode))
    }
}

/** Writes [bytes] to [path] under [root], rw-r--r--, making each folder on the way rwxr-xr-x. */
internal fun place(bytes: ByteArray, root: File, path: String) {
    val target = root.resolve(path)
    for (folder in generateSequence(target.parentFile) { it.parentFile }.takeWhile { it != root }.toList().reversed()) {
        if (folder.mkdir()) Files.setPosixFilePermissions(folder.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
    }
    target.writeBytes(bytes)
    Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
}

/** Runs [command] and fails with its output where it exits with anything but 0. */
internal fun run(vararg command: String) {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputReader().readText()
    check(process.waitFor() == 0) { "${command.joinToString(" ")} failed: $output" }
}
