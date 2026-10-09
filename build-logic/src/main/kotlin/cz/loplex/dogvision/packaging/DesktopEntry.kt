package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Writes an application's desktop entry, which puts it in the desktop's menu, named in each language [strings] has:
 * `Name=` from values/strings.xml, and `Name[cs]=` and the like from values-cs/strings.xml,
 * so that the menu names the application in the system's language, as the window's title does,
 * each with [nameSuffix] after it.\
 * Its `Comment=`, which the menu shows beside the name, comes from [commentString],
 * and its `Keywords=`, the words the menu finds it by besides its name, from [keywordsString], in each language alike.
 */
abstract class DesktopEntry : DefaultTask() {
    /** Android's strings.xml files, as texts has them: values/ in English, values-<language>/ in each other one. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val strings: DirectoryProperty

    /** The string the name is, such as app_name. */
    @get:Input
    abstract val nameString: Property<String>

    /**
     * What follows the name in every language, such as " (Java Swing)", where two packages share one; none by default.
     */
    @get:Input
    abstract val nameSuffix: Property<String>

    /** The string the keywords are, separated and ended by semicolons, such as desktop_keywords. */
    @get:Input
    abstract val keywordsString: Property<String>

    /** The string the comment is, such as desktop_comment. */
    @get:Input
    abstract val commentString: Property<String>

    /** The command, as the desktop runs it from PATH, with its arguments. */
    @get:Input
    abstract val exec: Property<String>

    /** The icon's name in the icon theme, with no extension. */
    @get:Input
    abstract val icon: Property<String>

    @get:Input
    abstract val categories: ListProperty<String>

    /**
     * The WM_CLASS of the application's windows,
     * by which the desktop tells them to be this entry's and shows its name and icon for them;
     * none where the windows are another program's, as a web browser's are.
     */
    @get:Input
    @get:Optional
    abstract val startupWmClass: Property<String>

    @get:OutputFile
    abstract val entry: RegularFileProperty

    init {
        nameSuffix.convention("")
    }

    @TaskAction
    fun write() {
        val folders = strings.get().asFile.listFiles { file -> file.isDirectory && file.name.startsWith("values") }
            .orEmpty().sortedBy { it.name }

        /** The key [key] in each language of [string], with [suffix] after it; in English at least. */
        fun localized(key: String, string: String, suffix: String = ""): List<String> {
            val lines = folders.mapNotNull { folder ->
                val value = androidString(folder.resolve("strings.xml"), string)?.plus(suffix) ?: return@mapNotNull null
                // values-pt-rBR is pt_BR in a desktop entry's key.
                val language = folder.name.removePrefix("values").removePrefix("-").replace("-r", "_")
                if (language.isEmpty()) "$key=$value" else "$key[$language]=$value"
            }
            check(lines.any { it.startsWith("$key=") }) { "values/strings.xml has no $string" }
            return lines
        }
        val lines = listOf("[Desktop Entry]", "Type=Application") +
            localized("Name", nameString.get(), nameSuffix.get()) +
            localized("Comment", commentString.get()) +
            listOf(
                "Exec=${exec.get()}",
                "Icon=${icon.get()}",
                "Terminal=false",
                "Categories=${categories.get().joinToString("") { "$it;" }}",
            ) +
            localized("Keywords", keywordsString.get()) +
            listOfNotNull(startupWmClass.orNull?.let { "StartupWMClass=$it" })
        entry.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /** The string [name] of the strings.xml [file], unescaped as Android reads it, or null where it has none. */
    private fun androidString(file: File, name: String): String? {
        if (!file.isFile) return null
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = document.getElementsByTagName("string")
        val element = (0 until strings.length).map { strings.item(it) }
            .firstOrNull { it.attributes.getNamedItem("name")?.nodeValue == name } ?: return null
        return element.textContent.replace("\\'", "'").replace("\\\"", "\"")
    }
}
