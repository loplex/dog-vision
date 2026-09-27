package cz.loplex.dogvision.packaging

import java.io.File

/** The ELF files under [folder]: executables and shared libraries, whatever their names. */
internal fun elfFiles(folder: File): List<File> {
    val elfMagic = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    return folder.walk()
        .filter { file -> file.isFile && file.inputStream().use { it.readNBytes(4) }.contentEquals(elfMagic) }
        .toList()
}
