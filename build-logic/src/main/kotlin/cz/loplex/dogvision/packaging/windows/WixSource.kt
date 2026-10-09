package cz.loplex.dogvision.packaging.windows

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Writes in [destination] what WiX Toolset 3's candle.exe and light.exe make the MSI of,
 * which the scripts in tools hand them:
 *
 * - [product], the MSI's source as it is, which includes variables.wxi and refers to what files.wxs defines.
 * - variables.wxi: the preprocessor variables [product] takes,
 *   each of this task's values, the paths of its files, and ProductCode, as [productCode] says.
 * - files.wxs: every file of [image] but app/.jpackage.xml, jpackage's record of the image,
 *   which its own MSIs leave out too, each a component of its own whose key path it is,
 *   in the folder INSTALLDIR, which [product] defines.\
 *   A launcher's files are in a component group of its own, which [product]'s Feature of that launcher takes:
 *   Launcher.dog_vision_swing holds dog-vision-swing.exe, app/dog-vision-swing.cfg
 *   and each JAR on that .cfg's classpath and on no other launcher's.\
 *   Every other file is in the component group Files.\
 *   Every file of [web] is in the folder web in INSTALLDIR,
 *   in the component group Web, but a source map, a .map, in Web.SourceMap.
 * - [codePage], the localization that sets the MSI's code page, as it is.
 *
 * A file directly in [image] has an ID of its name, its dashes as underscores, as dog_vision_compose.exe,
 * and one directly in [web] of web_ and its name, as web_index.html, by which [product] refers to them;
 * every other file, folder and component one of a hash of its path in INSTALLDIR.\
 * Each component's GUID is made of [upgradeCode] and that path,
 * so that it stays the same while the file is in the same place,
 * as Windows Installer counts the installations of a component by its GUID.
 */
abstract class WixSource : DefaultTask() {
    /** The app image the MSI installs, with the launchers' .cfg as [WindowsLauncherConfigs] writes them. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val image: DirectoryProperty

    /** The web page, which jpackage's image does not hold, as a browser opens it, its script's source map with it. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val web: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val product: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val codePage: RegularFileProperty

    /** The product's name, which the installer, the system's list of programs and the Start menu show. */
    @get:Input
    abstract val productName: Property<String>

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val vendor: Property<String>

    @get:Input
    abstract val packageDescription: Property<String>

    /**
     * The MSI's upgrade code: every later version's MSI replaces the one installed with the same code,
     * as Windows Installer tells versions of one product apart by it.\
     * Never change it, nor give two products one, as installing the one would then remove the other.
     */
    @get:Input
    abstract val upgradeCode: Property<String>

    /** The name of the folder in Program Files that the MSI installs into. */
    @get:Input
    abstract val installFolder: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val icon: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val license: RegularFileProperty

    /** The pictures of the MSI's dialogs, as windowsBitmaps draws them. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val banner: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val dialog: RegularFileProperty

    /** Whether candle.exe runs under Wine, which sees the paths through its drive Z:, rather than on Windows. */
    @get:Input
    abstract val underWine: Property<Boolean>

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun write() {
        val out = destination.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        product.get().asFile.copyTo(File(out, product.get().asFile.name))
        codePage.get().asFile.copyTo(File(out, codePage.get().asFile.name))
        File(out, "variables.wxi").writeText(variables(), Charsets.UTF_8)
        File(out, "files.wxs").writeText(files(), Charsets.UTF_8)
    }

    /**
     * The product's code, which tells this version of the product apart from every other:
     * made of [upgradeCode] and [version], as jpackage makes its,
     * so that an MSI of the version installed is taken for the same product,
     * which Windows Installer then repairs or removes, rather than installed beside it.
     */
    private fun productCode(): UUID = uuid("${upgradeCode.get()}/${version.get()}")

    private fun variables(): String {
        val values = listOf(
            "ProductName" to productName.get(),
            "ProductCode" to productCode().toString(),
            "UpgradeCode" to upgradeCode.get(),
            "Version" to version.get(),
            "Vendor" to vendor.get(),
            "Description" to packageDescription.get(),
            "InstallFolder" to installFolder.get(),
            "Icon" to path(icon.get().asFile),
            "License" to path(license.get().asFile),
            "Banner" to path(banner.get().asFile),
            "Dialog" to path(dialog.get().asFile),
        )
        val defines = values.joinToString("") { (name, value) ->
            // A define's value ends at its quote, and $( in it would start a variable.
            check('"' !in value && "$(" !in value && "?>" !in value) { "$name's value cannot be defined: $value" }
            "  <?define $name = \"$value\" ?>\n"
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- Written by the $path task. -->\n" +
            "<Include>\n$defines</Include>\n"
    }

    private fun files(): String {
        val root = image.get().asFile
        val page = web.get().asFile
        check(!File(root, WEB).exists()) { "$root holds $WEB, where the web page goes" }
        val groups = launcherGroups(root)
        val components = sortedMapOf(FILES to mutableListOf<String>())
        val grouped = groups.keys.toMutableSet()
        val body = StringBuilder()

        // The files of the folder [top], [under] in INSTALLDIR,
        // each in the component group [groups] gives it or else in the one [group] gives it.
        fun folder(top: File, under: String, group: (String) -> String, directory: File, depth: Int) {
            val indent = "  ".repeat(depth)
            for (entry in directory.listFiles().orEmpty().sortedBy { it.name }) {
                val relative = under + entry.relativeTo(top).invariantSeparatorsPath
                if (relative == JPACKAGE_RECORD) continue
                if (entry.isDirectory) {
                    body.append("$indent<Directory Id=\"d${hash(relative)}\" Name=\"${xml(entry.name)}\">\n")
                    folder(top, under, group, entry, depth + 1)
                    body.append("$indent</Directory>\n")
                } else {
                    val file = if (directory == top) {
                        (under.replace('/', '_') + entry.name).replace('-', '_')
                    } else {
                        "f${hash(relative)}"
                    }
                    check(Regex("[A-Za-z_][A-Za-z0-9_.]*").matches(file)) { "$relative gives no WiX ID: $file" }
                    val component = "c${hash(relative)}"
                    components.getOrPut(groups[relative] ?: group(relative), ::mutableListOf) += component
                    grouped -= relative
                    body.append(
                        "$indent<Component Id=\"$component\" Guid=\"{${uuid("${upgradeCode.get()}/$relative")}}\"" +
                            " Win64=\"yes\">\n" +
                            "$indent  <File Id=\"$file\" KeyPath=\"yes\" Source=\"${xml(path(entry))}\"/>\n" +
                            "$indent</Component>\n",
                    )
                }
            }
        }
        folder(root, "", { FILES }, root, 3)
        body.append("      <Directory Id=\"d${hash(WEB)}\" Name=\"$WEB\">\n")
        folder(page, "$WEB/", { if (it.endsWith(".map")) WEB_SOURCE_MAP_GROUP else WEB_GROUP }, page, 4)
        body.append("      </Directory>\n")
        check(components[WEB_GROUP].orEmpty().isNotEmpty()) { "$page holds no file but source maps" }
        check(components[WEB_SOURCE_MAP_GROUP].orEmpty().isNotEmpty()) { "$page holds no source map" }
        check(grouped.isEmpty()) { "A launcher's .cfg names what $root does not hold: $grouped" }
        check(components.getValue(FILES).isNotEmpty()) { "$root holds no file but its launchers'" }

        val references = components.entries.joinToString("") { (group, members) ->
            "    <ComponentGroup Id=\"$group\">\n" +
                members.joinToString("") { "      <ComponentRef Id=\"$it\"/>\n" } +
                "    </ComponentGroup>\n"
        }
        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- Written by the $path task: every file of the app image, each its own component, by launcher. -->
            |<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">
            |  <Fragment>
            |    <DirectoryRef Id="INSTALLDIR">
            |$body    </DirectoryRef>
            |$references  </Fragment>
            |</Wix>
            |
        """.trimMargin()
    }

    /**
     * The component group of each file of [root] that belongs to one launcher alone, by the file's path in [root]:
     * each .exe directly in [root] is a launcher,
     * which the group Launcher. and its name, its dashes as underscores, holds
     * with its app/ .cfg and each JAR on that .cfg's classpath and on no other launcher's.
     */
    private fun launcherGroups(root: File): Map<String, String> {
        val launchers = root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".exe") }.map { it.name }
        check(launchers.isNotEmpty()) { "$root holds no launcher" }
        val classpaths = launchers.associateWith { launcher ->
            val config = File(root, "app/${launcher.removeSuffix(".exe")}.cfg")
            check(config.isFile) { "$launcher has no ${config.relativeTo(root)}" }
            config.readLines(Charsets.UTF_8).filter { it.startsWith(LAUNCHER_CLASSPATH) }.map { line ->
                val entry = line.removePrefix(LAUNCHER_CLASSPATH).trimEnd()
                check(entry.startsWith($$"$APPDIR\\")) { "$config's classpath holds a file outside app: $entry" }
                "app/" + entry.removePrefix($$"$APPDIR\\").replace('\\', '/')
            }
        }
        val shared = classpaths.values.flatten().groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return classpaths.entries.flatMap { (launcher, classpath) ->
            val name = launcher.removeSuffix(".exe")
            val group = "Launcher." + name.replace('-', '_')
            check(Regex("[A-Za-z_][A-Za-z0-9_.]*").matches(group)) { "$launcher gives no WiX ID: $group" }
            (listOf(launcher, "app/$name.cfg") + (classpath - shared)).map { it to group }
        }.toMap()
    }

    /** [file]'s path as candle.exe sees it: Windows's own, or under Wine through its drive Z:. */
    private fun path(file: File): String =
        if (underWine.get()) "Z:" + file.absolutePath.replace('/', '\\') else file.absolutePath

    private fun hash(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** A name-based UUID of [text], which is the same each time it is made of the same text. */
    private fun uuid(text: String): UUID = UUID.nameUUIDFromBytes(text.toByteArray(Charsets.UTF_8))

    private fun xml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        const val JPACKAGE_RECORD = "app/.jpackage.xml"

        /** The component group of the files that belong to no launcher alone. */
        const val FILES = "Files"

        /** The web page's folder in INSTALLDIR. */
        const val WEB = "web"

        /** The component group of the web page's files. */
        const val WEB_GROUP = "Web"

        /** The component group of the web page's source maps. */
        const val WEB_SOURCE_MAP_GROUP = "Web.SourceMap"
    }
}
