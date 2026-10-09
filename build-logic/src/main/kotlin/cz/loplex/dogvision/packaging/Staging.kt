package cz.loplex.dogvision.packaging

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Copies [tree] into [into] with the modes a package installs:
 * folders rwxr-xr-x, files rwxr-xr-x where they are executable and rw-r--r-- otherwise,
 * whatever the build's umask and the modes the files had in Gradle's caches.
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
    makeFolders(root, target)
    target.writeBytes(bytes)
    Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
}

/**
 * Puts a symbolic link at [path] under [root] to [target], as it is written, a path relative to the link's folder,
 * making each folder on the way rwxr-xr-x: a link a package installs, whose target another package may install.
 */
internal fun link(root: File, path: String, target: String) {
    val link = root.resolve(path)
    makeFolders(root, link)
    Files.createSymbolicLink(link.toPath(), File(target).toPath())
}

/** Makes each folder on the way from [root] to [file] that is not there yet, rwxr-xr-x. */
private fun makeFolders(root: File, file: File) {
    for (folder in generateSequence(file.parentFile) { it.parentFile }.takeWhile { it != root }.toList().reversed()) {
        if (folder.mkdir()) Files.setPosixFilePermissions(folder.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
    }
}

/** [bytes] compressed by gzip -9n, as Debian Policy asks of a changelog and a manual page: no name, no time in it. */
internal fun gzip(bytes: ByteArray): ByteArray {
    val process = ProcessBuilder("gzip", "-9n").start()
    process.outputStream.use { it.write(bytes) }
    val compressed = process.inputStream.readBytes()
    check(process.waitFor() == 0) { "gzip failed: ${process.errorReader().readText()}" }
    return compressed
}

/** Runs [command] and fails with its output where it exits with anything but 0. */
internal fun run(vararg command: String) {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputReader().readText()
    check(process.waitFor() == 0) { "${command.joinToString(" ")} failed: $output" }
}
