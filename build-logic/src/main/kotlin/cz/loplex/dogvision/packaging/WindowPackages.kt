package cz.loplex.dogvision.packaging

import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider

/**
 * What a window's deb depends on that its natives do not name: a Java that can open a window,
 * the distribution's default where it is 17 or newer, as the Debian Java Policy has it;
 * LWJGL opens libEGL once it runs, and ffmpeg runs apart for a video or the camera.
 */
val windowDebDepends = listOf("default-jre (>= 2:1.17) | java17-runtime", "libegl1", "ffmpeg")

/**
 * What a window's rpm requires besides the libraries its natives link against:
 * a Java that can open a window, as the command line's rpm says why; libEGL, which LWJGL opens once it runs;
 * and ffmpeg's command rather than a package, as Fedora has two ffmpeg packages.
 */
val windowRpmRequires = listOf("/bin/sh", "(jre-17 or jre-21 or jre-25)", "libEGL.so.1()(64bit)", "/usr/bin/ffmpeg")

/**
 * Registers a window's deb and rpm, [packageName], on the system's Java, with the tasks they are made from,
 * each named after the package, as packageDogVisionSwingDeb and dogVisionSwingTree are dog-vision-swing's:
 * [jars], the window's JARs, in /usr/share/[packageName]/lib,
 * the natives they load unpacked in /usr/lib/[packageName], where the FHS puts what depends on the architecture,
 * a launcher in /usr/bin, which finds a Java 17 or newer, and the desktop entry and the icons, named [applicationId].\
 * The command line is the package dog-vision-cli, which each recommends.
 *
 * - [shared] resolves the JARs of [jars] that the package [sharedPackage] installs,
 *   in /usr/share/[sharedPackage]/lib, which the packages depend on at their own version and leave out.
 * - [mainClass] is the class the launcher starts, its main the window's.
 * - [jvmOptions] are the launcher's options before org.lwjgl.librarypath's, given the folder of the natives.
 * - [nameSuffix] follows the desktop entry's name in each language.
 * - [summary] and [description] are what a package manager lists the packages with, in its search and its details.
 * - [nativesLeftIn] names the JARs, by the start of their names, whose natives are not unpacked.
 * - [rpmRequires] is what the rpm requires besides what every window's does.
 *
 * Returns the task that lays out the files the packages install, less the shared JARs.
 */
fun Project.windowPackages(
    packageName: String,
    jars: NamedDomainObjectProvider<out Configuration>,
    shared: NamedDomainObjectProvider<out Configuration>,
    sharedPackage: String,
    applicationId: String,
    mainClass: String,
    jvmOptions: (natives: String) -> List<String> = { emptyList() },
    nameSuffix: String = "",
    summary: String,
    description: String,
    nativesLeftIn: List<String> = emptyList(),
    rpmRequires: List<String> = emptyList(),
): TaskProvider<Sync> {
    val home = "/usr/share/$packageName"
    // The files the packages are made of, in a folder of the package's own.
    val work = "linux/$packageName"
    val nativesHome = "/usr/lib/$packageName"
    val linuxJars = files(jars)
    val sharedJars = files(shared)
    // The names of the JARs in the lib folder that are not their own.
    val linuxJarNames = jars.flatMap { it.incoming.artifacts.resolvedArtifacts }.map(::installedJarNames)
    // The package's name in the tasks' names, DogVisionSwing and dogVisionSwing of dog-vision-swing.
    val suffix = packageName.split('-').joinToString("") { word -> word.replaceFirstChar(Char::uppercase) }
    val prefix = suffix.replaceFirstChar(Char::lowercase)
    // The window's icons, which every package of the project installs.
    val icons = rootProject.layout.projectDirectory.dir("gui-compose/packaging")

    // Every native library in the JARs,
    // which skiko and LWJGL would otherwise unpack at run time into the user's home or /tmp.
    val linuxNatives = tasks.register("${prefix}Natives", UnpackNatives::class.java) {
        this.description = "Unpacks the Linux natives out of the JARs into build/$work/natives, for " +
            "its deb and its rpm to install in $nativesHome."
        this.jars.from(linuxJars.filter { jar -> nativesLeftIn.none { jar.name.startsWith(it) } })
        natives.set(layout.buildDirectory.dir("$work/natives"))
    }

    val linuxLauncher = tasks.register("${prefix}Launcher", JavaLauncher::class.java) {
        this.description = "Writes build/$work/launcher/$packageName, the script its deb and its rpm install in " +
            "/usr/bin, which starts the window on the system's Java 17 or newer, not a headless one."
        commandName.set(packageName)
        this.mainClass.set(mainClass)
        this.jars.from(linuxJars)
        jarDirectory.set("$home/lib")
        this.sharedJars.from(sharedJars)
        sharedJarDirectory.set("/usr/share/$sharedPackage/lib")
        jarNames.set(linuxJarNames)
        this.jvmOptions.set(jvmOptions(nativesHome) + "-Dorg.lwjgl.librarypath=$nativesHome")
        minimumJava.set(17)
        opensWindow.set(true)
        script.set(layout.buildDirectory.file("$work/launcher/$packageName"))
    }

    val linuxDesktopEntry = tasks.register("${prefix}DesktopEntry", DesktopEntry::class.java) {
        this.description = "Writes build/$work/$applicationId.desktop, the desktop entry that puts the window in " +
            "the desktop's menu, named in each language of texts/strings" +
            if (nameSuffix.isEmpty()) "." else " with \"$nameSuffix\" after it."
        strings.set(rootProject.layout.projectDirectory.dir("texts/strings"))
        nameString.set("app_name")
        this.nameSuffix.set(nameSuffix)
        commentString.set("desktop_comment")
        exec.set(packageName)
        icon.set(applicationId)
        categories.set(listOf("Graphics"))
        // Java names each window's WM_CLASS after the class its main is in, the dots as dashes.
        startupWmClass.set(mainClass.replace('.', '-'))
        entry.set(layout.buildDirectory.file("$work/$applicationId.desktop"))
    }

    val linuxTree = tasks.register("${prefix}Tree", Sync::class.java) {
        this.description = "Lays out in build/$work/tree the files its deb and its rpm install: the " +
            "JARs, the natives, the launcher, the desktop entry and the icons."
        into(layout.buildDirectory.dir("$work/tree"))
        from(linuxJars.minus(sharedJars)) {
            into(home.removePrefix("/") + "/lib")
            exclude(NativesOnly)
            renameJars(linuxJarNames)
        }
        from(linuxNatives) { into(nativesHome.removePrefix("/")) }
        from(linuxLauncher) { into("usr/bin") }
        from(linuxDesktopEntry) { into("usr/share/applications") }
        from(icons.file("dog-vision.png")) {
            into("usr/share/icons/hicolor/256x256/apps")
            rename("dog-vision.png", "$applicationId.png")
        }
        from(icons.file("dog-vision.svg")) {
            into("usr/share/icons/hicolor/scalable/apps")
            rename("dog-vision.svg", "$applicationId.svg")
        }
    }

    val rpmLibraryRequires = tasks.register("${prefix}RpmLibraryRequires", RpmLibraryRequires::class.java) {
        this.description = "Lists in build/$work/rpmLibraryRequires.txt the libraries the natives " +
            "link against and do not bring themselves, which its rpm requires."
        image.set(linuxNatives.flatMap { it.natives })
        requires.set(layout.buildDirectory.file("$work/rpmLibraryRequires.txt"))
    }

    val debDepends = tasks.register("${prefix}DebDepends", DebDepends::class.java) {
        this.description = "Writes its deb's Depends into build/$work/debDepends.txt: the " +
            "packages of the libraries the natives link against and do not bring, then a Java that can open a " +
            "window, libEGL and ffmpeg."
        image.set(linuxNatives.flatMap { it.natives })
        packages.set(debianPackages)
        others.set(windowDebDepends)
        depends.set(layout.buildDirectory.file("$work/debDepends.txt"))
    }

    // What the JARs and their natives hold that is not this project's own, the shared JARs left to their package.
    val licences = thirdPartyLicenses("${prefix}Licences", listOf(jars), listOf(shared), unpacksNatives = true) {
        artifactName.set("The package $packageName")
    }

    val packageDeb = tasks.register("package${suffix}Deb", DebPackage::class.java) {
        this.description = "Packs build/distributions/${packageName}_<version>_amd64.deb, on the system's Java."
        group = "distribution"
        tree.set(layout.dir(linuxTree.map { it.destinationDir }))
        this.packageName.set(packageName)
        architecture.set("amd64")
        this.summary.set(summary)
        longDescription.set(description)
        depends.set(debDepends.flatMap { it.depends }.map { it.asFile.readText().split(", ") })
        depends.add(this.version.map { "$sharedPackage (= $it)" })
        recommends.set(listOf("dog-vision-cli"))
        license.set(licences.flatMap { it.copyright })
    }

    val packageRpm = tasks.register("package${suffix}Rpm", RpmPackage::class.java) {
        this.description = "Packs build/distributions/$packageName-<version>-1.x86_64.rpm, on the system's Java."
        group = "distribution"
        tree.set(layout.dir(linuxTree.map { it.destinationDir }))
        this.packageName.set(packageName)
        architecture.set("x86_64")
        this.summary.set(summary)
        longDescription.set(description)
        // The libraries the natives need,
        // as rpm's own generator names them, since Fedora's and openSUSE's package names differ,
        // what every window's rpm requires, and the shared JARs' package of this version.
        requires.set(
            rpmLibraryRequires.flatMap { it.requires }.map { file ->
                file.asFile.readText().split(',') + windowRpmRequires + rpmRequires
            },
        )
        requires.add(this.version.zip(release) { version, release -> "$sharedPackage = $version-$release" })
        recommends.set(listOf("dog-vision-cli"))
    }
    artifact(packageDeb)
    artifact(packageRpm)
    return linuxTree
}
