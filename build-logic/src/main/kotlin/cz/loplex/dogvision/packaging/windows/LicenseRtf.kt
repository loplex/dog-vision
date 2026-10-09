package cz.loplex.dogvision.packaging.windows

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes [rtf], the licence the MSI's dialog shows, which WiX takes as an RTF file, from [text], line for line,
 * as the text has its lines broken by hand, so that a title does not run into the line below it.\
 * Every line ends with CRLF: the dialog under Wine draws a lone LF as a box.
 */
abstract class LicenseRtf : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val text: RegularFileProperty

    @get:OutputFile
    abstract val rtf: RegularFileProperty

    @TaskAction
    fun write() {
        val lines = text.get().asFile.readText().removeSuffix("\n").lines()
        val body = lines.joinToString("") { line -> if (line.isBlank()) "\\par\r\n" else "${escape(line)}\\line\r\n" }
        // Arial at 9 points, in which all but the longest lines fit the dialog's width.
        val header = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fswiss Arial;}}\r\n\\f0\\fs18\r\n"
        rtf.get().asFile.writeText("$header$body}\r\n", Charsets.US_ASCII)
    }

    /** The line with RTF's own characters escaped, and each other than ASCII as its UTF-16 unit. */
    private fun escape(line: String): String = buildString {
        for (char in line.trimEnd('\r')) {
            when {
                char == '\\' || char == '{' || char == '}' -> append('\\').append(char)
                char.code < 0x80 -> append(char)
                else -> append("\\u").append(char.code.toShort()).append('?')
            }
        }
    }
}
