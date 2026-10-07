package cz.loplex.dogvision.packaging

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import java.io.File

/** The version of the packages for Windows: appVersion, or -PwindowsAppVersion, as a test of an upgrade needs. */
private fun Project.windowsVersion(): Provider<String> =
    providers.gradleProperty("windowsAppVersion").orElse(providers.gradleProperty("appVersion"))

/** Whether the scripts in tools run jpackage and WiX under Wine, rather than on Windows. */
private fun underWine(): Boolean = !System.getProperty("os.name").startsWith("Windows")

/** The app image that -PwindowsAppImage names, as the scripts in tools have jpackage make it. */
private fun Project.builtAppImage() = layout.dir(providers.gradleProperty("windowsAppImage").map(::File))

/** The folder in build of an image's files for Windows, of [name], as windowsAppImage takes it: build/windows/cli. */
internal fun windowsFolder(name: String) = if (name.isEmpty()) "windows" else "windows/${name.lowercase()}"

/**
 * Registers what the Windows app image [packageName] is made of,
 * [launchers] on [runtime], the launcher [packageName] the main one.\
 * [name] is the image's in the tasks' names and their folders', where a module makes more than one image:
 * with Cli, windowsCliJpackage writes build/windows/cli/jpackage.
 * Without it:
 *
 * - windowsJpackage: build/windows/jpackage, what jpackage makes the image of, as [WindowsJpackageFiles] says.
 * - windowsLauncherConfigs: build/windows/launchers,
 *   the .cfg of the launchers of the image that -PwindowsAppImage names,
 *   each with the classpath its launcher's .classpath names, as [WindowsLauncherConfigs] says,
 *   which the scripts copy into the image; and LICENSE and [notices],
 *   what the image holds that is not this project's own, which they copy into the image's folder.
 */
fun Project.windowsAppImage(
    packageName: String,
    description: String,
    launchers: FileCollection,
    runtime: TaskProvider<RuntimeImage>,
    notices: Provider<RegularFile>,
    name: String = "",
): TaskProvider<WindowsJpackageFiles> {
    val folder = windowsFolder(name)
    tasks.register("windows${name}LauncherConfigs", WindowsLauncherConfigs::class.java) {
        this.description = "Writes build/$folder/launchers, the .cfg of the launchers of -PwindowsAppImage's " +
            "image, each with its own classpath."
        group = "distribution"
        image.set(builtAppImage())
        classpaths.from(launchers.asFileTree.matching { include("**/*.classpath") })
        license.set(rootProject.layout.projectDirectory.file("LICENSE"))
        this.notices.set(notices)
        destination.set(layout.buildDirectory.dir("$folder/launchers"))
    }
    return tasks.register("windows${name}Jpackage", WindowsJpackageFiles::class.java) {
        this.description = "Writes build/$folder/jpackage, what jpackage makes the app image $packageName for " +
            "Windows of."
        group = "distribution"
        this.packageName.set(packageName)
        version.set(windowsVersion())
        vendor.set(VENDOR)
        packageDescription.set(description)
        icon.set(rootProject.layout.projectDirectory.file("gui-compose/packaging/dog-vision.ico"))
        this.launchers.from(launchers)
        this.runtime.set(runtime.flatMap { it.destination })
        underWine.set(underWine())
        destination.set(layout.buildDirectory.dir("$folder/jpackage"))
    }
}

/**
 * Registers what the MSI of the app image that -PwindowsAppImage names is made of,
 * which the scripts in tools build with windowsAppImage's arguments first,
 * and give its launchers' .cfg from its windowsLauncherConfigs:
 *
 * - windowsLicense: build/windows/LICENSE.rtf, the licence the MSI shows.
 * - windowsBitmaps: build/windows/banner.bmp and dialog.bmp, the pictures of the MSI's dialogs.
 * - windowsWix: build/windows/wix, [product] and what it takes, as [WixSource] says, [codePage] among it,
 *   which the scripts hand candle.exe and light.exe once they have copied the .cfg.
 *
 * The product's name is the app's English name, texts' app_name; [configure] gives windowsWix the rest.
 */
fun Project.windowsMsi(product: RegularFile, codePage: RegularFile, configure: WixSource.() -> Unit) {
    val root = rootProject.layout.projectDirectory
    val image = builtAppImage()
    val license = tasks.register("windowsLicense", LicenseRtf::class.java) {
        description = "Writes build/windows/LICENSE.rtf, the licence the MSI shows, line for line."
        group = "distribution"
        text.set(root.file("LICENSE"))
        rtf.set(layout.buildDirectory.file("windows/LICENSE.rtf"))
    }
    val bitmaps = tasks.register("windowsBitmaps", InstallerBitmaps::class.java) {
        description = "Draws build/windows/banner.bmp and dialog.bmp, the pictures of the MSI's dialogs."
        group = "distribution"
        icon.set(root.file("gui-compose/packaging/dog-vision.png"))
        background.set(root.file("android/src/main/res/values/ic_launcher_background.xml"))
        banner.set(layout.buildDirectory.file("windows/banner.bmp"))
        dialog.set(layout.buildDirectory.file("windows/dialog.bmp"))
    }
    val strings = root.file("texts/strings/values/strings.xml")
    val appName = providers.fileContents(strings).asText.map { text ->
        checkNotNull(APP_NAME.find(text)) { "${strings.asFile} has no app_name" }.groupValues[1]
    }
    tasks.register("windowsWix", WixSource::class.java) {
        description = "Writes build/windows/wix, what WiX makes the MSI of -PwindowsAppImage's image of."
        group = "distribution"
        this.image.set(image)
        this.product.set(product)
        this.codePage.set(codePage)
        productName.set(appName)
        version.set(windowsVersion())
        vendor.set(VENDOR)
        icon.set(root.file("gui-compose/packaging/dog-vision.ico"))
        this.license.set(license.flatMap { it.rtf })
        banner.set(bitmaps.flatMap { it.banner })
        dialog.set(bitmaps.flatMap { it.dialog })
        underWine.set(underWine())
        destination.set(layout.buildDirectory.dir("windows/wix"))
        configure()
    }
}

private val APP_NAME = Regex("""<string name="app_name">([^<]+)</string>""")
