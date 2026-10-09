@file:Suppress("UnstableApiUsage")

import cz.loplex.dogvision.packaging.APACHE_TEXT
import cz.loplex.dogvision.packaging.DebDepends
import cz.loplex.dogvision.packaging.DebPackage
import cz.loplex.dogvision.packaging.DesktopEntry
import cz.loplex.dogvision.packaging.JavaLauncher
import cz.loplex.dogvision.packaging.ManPage
import cz.loplex.dogvision.packaging.RpmLibraryRequires
import cz.loplex.dogvision.packaging.RpmPackage
import cz.loplex.dogvision.packaging.ThirdPartyPart
import cz.loplex.dogvision.packaging.WEB_PAGE_USAGE
import cz.loplex.dogvision.packaging.WINDOWS_LAUNCHER_USAGE
import cz.loplex.dogvision.packaging.artifact
import cz.loplex.dogvision.packaging.debianPackages
import cz.loplex.dogvision.packaging.javaRuntimeNote
import cz.loplex.dogvision.packaging.jvmRuntimeOf
import cz.loplex.dogvision.packaging.temurinRelease
import cz.loplex.dogvision.packaging.thirdPartyLicenses
import cz.loplex.dogvision.packaging.windowDebDepends
import cz.loplex.dogvision.packaging.windowPackages
import cz.loplex.dogvision.packaging.windowRpmRequires
import cz.loplex.dogvision.packaging.windowTarGz
import cz.loplex.dogvision.packaging.windowsAppImage
import cz.loplex.dogvision.packaging.windowsMsi
import cz.loplex.dogvision.packaging.windowsRuntimeImage

// Packages made apart from the modules they hold:
// - the deb and the rpm of the JARs the command line runs on, which both windows run on as well,
//   and of the Compose window, the Swing window and the command line, on the system's Java,
//   each of the JARs its module runs on, which jvmRuntimeOf takes, less the shared ones;
// - the deb and the rpm of the web page, the folder web hands this module through webPage,
//   opened in the system's browser, and those of its script's source map;
// - the deb and the rpm dog-vision, of all of those but the source map's in one, which conflict with each of theirs;
// - the tar.gz of each window,
//   its app image with a runtime of its own and dog-vision-cli's launcher beside the window's;
// - the MSI for Windows on x86-64, of the Compose window, the Swing window and the command line,
//   each a launcher of one app image, on one runtime, and of the web page;
//   and the command line's zip, of its launcher alone.
//   Each of the windows' modules and cli hands this one its launcher through windowsLauncher.
//   The scripts in tools run jpackage and WiX over what this module writes.
plugins {
    base
    // The toolchains, for the JDK whose jlink links the runtime.
    `jvm-toolchains`
    id("cz.loplex.dogvision.packaging")
    id("cz.loplex.dogvision.ktlint")
}

val launchers = configurations.dependencyScope("windowsLaunchers")
val launcherFiles = configurations.resolvable("windowsLauncherFiles") {
    extendsFrom(launchers.get())
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(WINDOWS_LAUNCHER_USAGE)) }
}
dependencies {
    for (module in listOf(":gui-compose", ":gui-swing", ":cli")) launchers(project(module))
}

// The web page, as web hands it over, for its deb and rpm and the MSI.
val webPage = configurations.dependencyScope("webPage")
val webPageFiles = configurations.resolvable("webPageFiles") {
    extendsFrom(webPage.get())
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(WEB_PAGE_USAGE)) }
}
dependencies {
    webPage(project(":web"))
}

// What the page's script holds besides this project's own code: Kotlin's standard library for JavaScript,
// which the compiler builds in, and webpack's runtime, which bundles it; the source map holds their sources.
// No JVM configuration names them, so they are listed here, webpack at the version kotlin-js-store locks.
val webpackVersion = checkNotNull(
    Regex(""""node_modules/webpack": \{\s*"version": "([^"]+)"""")
        .find(rootProject.file("kotlin-js-store/package-lock.json").readText()),
) { "kotlin-js-store/package-lock.json locks no webpack" }.groupValues[1]

fun webParts(file: String) = listOf(
    ThirdPartyPart(
        name = "org.jetbrains.kotlin:kotlin-stdlib-js",
        version = libs.versions.kotlin.get(),
        licence = "Apache-2.0",
        holder = "JetBrains",
        text = APACHE_TEXT,
        files = listOf(file),
    ),
    ThirdPartyPart(
        name = "webpack",
        version = webpackVersion,
        licence = "MIT",
        holder = "JS Foundation and other contributors",
        text = "webpack.txt",
        files = listOf(file),
    ),
)

// The web page the MSI installs in its folder web, the script's source map in a part of its own.
val windowsWebPage = tasks.register<Sync>("windowsWebPage") {
    description = "Lays out in build/windows/web the web page the MSI installs, with the script's source map."
    into(layout.buildDirectory.dir("windows/web"))
    from(webPageFiles)
}

// What each Windows image holds that is not this project's own:
// the parts each launcher's JARs are, as the module hands them over,
// the web page's in the MSI, and the runtime, which keeps its notices in its own legal folder.
val windowsRuntimeNote = temurinRelease().map { javaRuntimeNote("runtime", it) }
val windowsLicences = thirdPartyLicenses("windowsLicences", emptyList()) {
    artifactName = "Dog Vision for Windows"
    includedParts.from(launcherFiles.get().filter { it.name.endsWith(".licences") })
    extraParts = webParts("dog-vision.js") + webParts("dog-vision.js.map")
    notes.add(windowsRuntimeNote)
}

// One runtime of every module a launcher needs, and dog-vision-compose.exe the image's main launcher,
// whose name the image's folder has.
windowsAppImage(
    packageName = "dog-vision-compose",
    description = "How a dog or another animal sees a photo, a video or the camera",
    launchers = launcherFiles.get(),
    runtime = windowsRuntimeImage(
        "the runtime of the MSI's launchers",
        modules = emptyList(),
        moduleLists = launcherFiles.get().filter { it.name.endsWith(".modules") },
    ),
    notices = windowsLicences.flatMap { it.notices },
)

// What jpackage makes the app image of the command line's zip for Windows on x86-64 of,
// as the scripts in tools hand it: the command line alone, on a runtime of its own, from the files cli hands over.
val cliLauncherFiles = launcherFiles.get().incoming.artifactView {
    componentFilter { it is ProjectComponentIdentifier && it.projectPath == ":cli" }
}.files
val windowsCliLicences = thirdPartyLicenses("windowsCliLicences", emptyList()) {
    artifactName = "The command line for Windows"
    includedParts.from(cliLauncherFiles.filter { it.name.endsWith(".licences") })
    notes.add(windowsRuntimeNote)
}
windowsAppImage(
    packageName = "dog-vision-cli",
    description = "How a dog or another animal sees a photo or a video, from the command line",
    launchers = cliLauncherFiles,
    runtime = windowsRuntimeImage(
        "the command line's runtime for Windows",
        modules = emptyList(),
        moduleLists = cliLauncherFiles.filter { it.name.endsWith(".modules") },
        name = "Cli",
    ),
    notices = windowsCliLicences.flatMap { it.notices },
    name = "Cli",
)

windowsMsi(
    product = layout.projectDirectory.file("windows/dog-vision.wxs"),
    codePage = layout.projectDirectory.file("windows/codepage.wxl"),
) {
    packageDescription = "How a dog or another animal sees a photo, a video or the camera"
    upgradeCode = "bd534b2e-fc9e-40d5-a461-b800bf54bcda"
    installFolder = "dog-vision"
    web = layout.dir(windowsWebPage.map { it.destinationDir })
}

tasks.register("packageDeb") {
    description = "Packs every deb into build/distributions."
    group = "distribution"
    dependsOn(tasks.withType<DebPackage>())
}
tasks.register("packageRpm") {
    description = "Packs every rpm into build/distributions."
    group = "distribution"
    dependsOn(tasks.withType<RpmPackage>())
}

// The deb and the rpm, dog-vision-common: the JARs the command line runs on,
// core's, texts' and Kotlin's among them, in /usr/share/dog-vision-common/lib, which both windows run on as well.
// dog-vision-cli and both windows' packages depend on it at their own version and leave its JARs out.
// It starts nothing itself, so it depends on no Java, and it has no natives, so one package serves every architecture.
val commonPackage = "dog-vision-common"
val commonHome = "/usr/share/$commonPackage"
val commonJars = jvmRuntimeOf(":cli")

// The manual page of the command line and both windows, which their packages link to under each command's name.
val manPage = tasks.register<ManPage>("dogVisionManPage") {
    description = "Compresses packaging/man/dog-vision.1 into build/linux/dog-vision-common/dog-vision.1.gz."
    page = layout.projectDirectory.file("man/dog-vision.1")
    compressed = layout.buildDirectory.file("linux/$commonPackage/dog-vision.1.gz")
}

/** The link a package of [command] installs to the manual page of dog-vision-common, which it depends on. */
fun manPageLink(command: String) = mapOf("usr/share/man/man1/$command.1.gz" to "dog-vision.1.gz")

val commonTree = tasks.register<Sync>("dogVisionCommonTree") {
    description = "Lays out in build/linux/dog-vision-common/tree the files its deb and its rpm install: the JARs " +
        "and the manual page."
    into(layout.buildDirectory.dir("linux/$commonPackage/tree"))
    from(commonJars) { into(commonHome.removePrefix("/") + "/lib") }
    from(manPage) { into("usr/share/man/man1") }
}

/** What the deb and the rpm are listed with, in a package manager's search and its details. */
val commonSummary = "How a dog or another animal sees colours (common files)"
val commonDescription = """
    dog-vision shows a photo, a video or the camera with the colours a dog,
    a cat or another animal can tell apart, beside the original.

    This package is what the command line and the desktop windows share,
    the packages dog-vision-cli, dog-vision-compose and dog-vision-swing:
    their Java libraries and their manual page. It does nothing on its own.
""".trimIndent()

// What its JARs are that is not this project's own.
val commonLicences = thirdPartyLicenses("dogVisionCommonLicences", listOf(commonJars)) {
    artifactName = "The package $commonPackage"
}

val packageCommonDeb = tasks.register<DebPackage>("packageDogVisionCommonDeb") {
    description = "Packs build/distributions/dog-vision-common_<version>_all.deb."
    group = "distribution"
    tree = layout.dir(commonTree.map { it.destinationDir })
    packageName = commonPackage
    architecture = "all"
    summary = commonSummary
    longDescription = commonDescription
    depends = emptyList()
    recommends = emptyList()
    license = commonLicences.flatMap { it.copyright }
}
artifact(packageCommonDeb)

val packageCommonRpm = tasks.register<RpmPackage>("packageDogVisionCommonRpm") {
    description = "Packs build/distributions/dog-vision-common-<version>-1.noarch.rpm."
    group = "distribution"
    tree = layout.dir(commonTree.map { it.destinationDir })
    packageName = commonPackage
    architecture = "noarch"
    summary = commonSummary
    longDescription = commonDescription
    requires = emptyList()
    recommends = emptyList()
    licenseName = commonLicences.flatMap { it.spdx }.map { it.asFile.readText() }
    thirdPartyLicenses = commonLicences.flatMap { it.notices }
}
artifact(packageCommonRpm)

/**
 * The lintian tags a deb of [packageName] overrides, each after why:
 * of what it bundles as the makers ship it, LWJGL's JARs, in the lib folder of [lwjglIn],
 * and, with [composeIn], Compose's JARs and skiko's library there.
 */
fun lintianOverrides(packageName: String, lwjglIn: String, composeIn: String? = null): String = buildString {
    appendLine("# LWJGL's JAR holds a module descriptor of Java 25, class file version 69, unknown to lintian.")
    appendLine("$packageName: unknown-java-class-version * [usr/share/$lwjglIn/lib/lwjgl-*.jar]")
    if (composeIn == null) return@buildString
    appendLine("# The desktop JARs JetBrains publishes of Compose and its AndroidX libraries that hold no class: each")
    appendLine("# hands over to the JAR of the same library that holds the code, and the class path names both.")
    appendLine("$packageName: codeless-jar [usr/share/$composeIn/lib/*-desktop-*.jar]")
    appendLine("# skiko, Skia for the JVM, as JetBrains builds it: with these libraries linked in, and not stripped.")
    appendLine("$packageName: embedded-library * [usr/lib/$composeIn/libskiko-linux-x64.so]")
    appendLine("$packageName: unstripped-binary-or-object [usr/lib/$composeIn/libskiko-linux-x64.so]")
    appendLine("# ICU's licence, which the copyright file quotes word for word, gives the FSF's old address.")
    appendLine("$packageName: old-fsf-address-in-copyright-file")
}

// The deb and the rpm, dog-vision-compose, on the system's Java.
val composePackage = "dog-vision-compose"
val composeJars = jvmRuntimeOf(":gui-compose")
val composeTree = windowPackages(
    packageName = composePackage,
    jars = composeJars,
    shared = commonJars,
    sharedPackage = commonPackage,
    // The application's ID, which the Windows MSI does not use.
    applicationId = "cz.loplex.dogvision.compose",
    mainClass = "cz.loplex.dogvision.desktop.MainKt",
    nameSuffix = " (Kotlin Compose)",
    // As jpackage's launcher passes them, but for the resources folder, which the window has no use for.
    jvmOptions = { natives ->
        listOf("-Dcompose.application.configure.swing.globals=true", "-Dskiko.library.path=$natives")
    },
    summary = "How a dog or another animal sees colours (Kotlin Compose GUI)",
    description = """
        dog-vision shows a photo, a video or the camera with the colours a dog,
        a cat or another animal can tell apart, beside the original.

        This package is the desktop window, in Compose. The package
        dog-vision-swing is the same window in Swing.

        The command line, which converts a photo or a video, is the package
        dog-vision-cli.
    """.trimIndent(),
    lintianOverrides = lintianOverrides(composePackage, composePackage, composePackage),
)

// The deb and the rpm, dog-vision-swing, on the system's Java, as the Compose window's dog-vision-compose,
// with the desktop entry and icons under names of their own, so that both windows install side by side.
val swingPackage = "dog-vision-swing"

// A font Java can use, without which Swing cannot start: openSUSE's JRE brings none, where Debian's fontconfig does.
// Named outright, DejaVu Sans as Fedora and Rocky package it, or DejaVu as openSUSE does:
// font(:lang=en) is met on openSUSE by xorg-x11-fonts-core, whose bitmap fonts Java does not read.
val swingRpmRequires = listOf("(dejavu-sans-fonts or dejavu-fonts)")

val swingJars = jvmRuntimeOf(":gui-swing")
val swingTree = windowPackages(
    packageName = swingPackage,
    jars = swingJars,
    shared = commonJars,
    sharedPackage = commonPackage,
    // Not the Compose window's cz.loplex.dogvision.compose.
    applicationId = "cz.loplex.dogvision.swing",
    mainClass = "cz.loplex.dogvision.swing.MainKt",
    nameSuffix = " (Java Swing)",
    summary = "How a dog or another animal sees colours (Java Swing GUI)",
    description = """
        dog-vision shows a photo, a video or the camera with the colours a dog,
        a cat or another animal can tell apart, beside the original.

        This package is the desktop window in Swing, which needs neither Compose
        nor skiko. The package dog-vision-compose is the same window in
        Compose.

        The command line, which converts a photo or a video, is the package
        dog-vision-cli.
    """.trimIndent(),
    // FlatLaf loads its natives on Linux only for window decorations of its own,
    // which the window does not use, and they link GTK 3.
    nativesLeftIn = listOf("flatlaf-"),
    rpmRequires = swingRpmRequires,
    lintianOverrides = lintianOverrides(swingPackage, swingPackage),
)

// The tar.gz of each window, on a runtime of the modules its launcher and dog-vision-cli's need,
// as each module's launcher for Windows names them.
fun launcherModules(vararg modules: String) = launcherFiles.get().incoming.artifactView {
    componentFilter { it is ProjectComponentIdentifier && it.projectPath in modules }
}.files.filter { it.name.endsWith(".modules") }
artifact(
    windowTarGz(
        packageName = composePackage,
        module = ":gui-compose",
        jars = composeJars,
        cliJars = commonJars,
        mainClass = "cz.loplex.dogvision.desktop.MainKt",
        // As Compose's launchers pass them, but for the resources folder, which the window has no use for:
        // the first has its application give Swing the system's look,
        // the second has skiko take its natives from beside the JARs.
        javaOptions = listOf("-Dcompose.application.configure.swing.globals=true", $$"-Dskiko.library.path=$APPDIR"),
        nativesBeside = listOf("skiko-awt-runtime-linux-"),
        moduleLists = launcherModules(":gui-compose", ":cli"),
    ),
)
artifact(
    windowTarGz(
        packageName = swingPackage,
        module = ":gui-swing",
        jars = swingJars,
        cliJars = commonJars,
        mainClass = "cz.loplex.dogvision.swing.MainKt",
        moduleLists = launcherModules(":gui-swing", ":cli"),
    ),
)
tasks.register("packageTarGz") {
    description = "Packs every tar.gz into build/distributions."
    group = "distribution"
    dependsOn(tasks.withType<Tar>())
}

// The deb and the rpm, dog-vision-cli, on the system's Java: a launcher in /usr/bin,
// which finds a Java 17 or newer and starts the command line from dog-vision-common's JARs.
// It has no natives, so one package serves every architecture,
// and it needs a headless Java only, as it opens no window.
val cliPackage = "dog-vision-cli"

val cliLauncher = tasks.register<JavaLauncher>("dogVisionCliLauncher") {
    description = "Writes build/linux/dog-vision-cli/launcher/dog-vision-cli, the script its deb and rpm install in " +
        "/usr/bin, which starts the command line on the system's Java 17 or newer."
    commandName = cliPackage
    mainClass = "cz.loplex.dogvision.cli.MainKt"
    jars.from(commonJars)
    jarDirectory = "$commonHome/lib"
    jvmOptions = emptyList()
    minimumJava = 17
    script = layout.buildDirectory.file("linux/$cliPackage/launcher/$cliPackage")
}

val cliTree = tasks.register<Sync>("dogVisionCliTree") {
    description = "Lays out in build/linux/dog-vision-cli/tree the file its deb and its rpm install: the launcher."
    into(layout.buildDirectory.dir("linux/$cliPackage/tree"))
    from(cliLauncher) { into("usr/bin") }
}

/** What the deb and the rpm are listed with, in a package manager's search and its details. */
val cliSummary = "How a dog or another animal sees colours (command line)"
val cliDescription = """
    dog-vision shows a photo, a video or the camera with the colours a dog,
    a cat or another animal can tell apart, beside the original.

    This package is the command line, which converts a photo or a video
    to those colours, as a PNG or an .mp4 beside it, with no window and
    no display.

    The desktop windows, which show a video or the camera as well, are
    the packages dog-vision-compose and dog-vision-swing.
""".trimIndent()

// It holds no JAR, only its launcher: nothing that is not this project's own.
val cliLicences = thirdPartyLicenses("dogVisionCliLicences", emptyList()) {
    artifactName = "The package $cliPackage"
}

val packageCliDeb = tasks.register<DebPackage>("packageDogVisionCliDeb") {
    description = "Packs build/distributions/dog-vision-cli_<version>_all.deb, on the system's Java."
    group = "distribution"
    tree = layout.dir(cliTree.map { it.destinationDir })
    packageName = cliPackage
    architecture = "all"
    summary = cliSummary
    longDescription = cliDescription
    // The distribution's default JRE where it is 17 or newer, as the Debian Java Policy has it,
    // and any JRE that provides java17-runtime-headless where it is not: Ubuntu 20.04's and 22.04's are 11.
    // The JARs of its own version.
    depends = version.map {
        listOf("default-jre-headless (>= 2:1.17) | java17-runtime-headless", "$commonPackage (= $it)")
    }
    // ffmpeg reads and writes a video; a photo needs none.
    recommends = listOf("ffmpeg")
    license = cliLicences.flatMap { it.copyright }
    links = manPageLink(cliPackage)
}
artifact(packageCliDeb)

val packageCliRpm = tasks.register<RpmPackage>("packageDogVisionCliRpm") {
    description = "Packs build/distributions/dog-vision-cli-<version>-1.noarch.rpm, on the system's Java."
    group = "distribution"
    tree = layout.dir(cliTree.map { it.destinationDir })
    packageName = cliPackage
    architecture = "noarch"
    summary = cliSummary
    longDescription = cliDescription
    // No one name that every rpm JRE of 17 or newer provides, and no older one:
    // jre-headless >= 17 would take Fedora's and Rocky's Java 8,
    // which provides it at epoch 1 and so above any version at epoch 0,
    // and Temurin's 8 and 11, which provide it with no version at all.
    // A new LTS joins the list when a distribution makes it its default.
    // The JARs of its own version, as the deb's.
    requires = version.zip(release) { version, release ->
        val java = "(jre-17-headless or jre-21-headless or jre-25-headless)"
        listOf("/bin/sh", java, "$commonPackage = $version-$release")
    }
    // ffmpeg's command, as the windows' rpms name it, for a video.
    recommends = listOf("/usr/bin/ffmpeg")
    links = manPageLink(cliPackage)
    licenseName = cliLicences.flatMap { it.spdx }.map { it.asFile.readText() }
    thirdPartyLicenses = cliLicences.flatMap { it.notices }
}
artifact(packageCliRpm)

// The deb and the rpm, dog-vision-web:
// the page in /usr/share/dog-vision-web, as web hands it over but for the script's source map,
// a command, /usr/bin/dog-vision-web, that opens its index.html in the system's browser through xdg-open,
// with a manual page of its own, and a desktop entry that runs it.
// It has no natives, so one package serves every architecture.
val webPackage = "dog-vision-web"
val webHome = "/usr/share/$webPackage"
val webApplicationId = "cz.loplex.dogvision.web"

val webDesktopEntry = tasks.register<DesktopEntry>("dogVisionWebDesktopEntry") {
    description = "Writes build/linux/dog-vision-web/$webApplicationId.desktop, the desktop entry that puts the page " +
        "in the desktop's menu, named in each language of texts/strings with \" (web)\" after it."
    strings = rootProject.layout.projectDirectory.dir("texts/strings")
    nameString = "app_name"
    nameSuffix = " (web)"
    commentString = "desktop_comment"
    exec = webPackage
    icon = webApplicationId
    categories = listOf("Graphics")
    entry = layout.buildDirectory.file("linux/$webPackage/$webApplicationId.desktop")
}

val webManPage = tasks.register<ManPage>("dogVisionWebManPage") {
    description = "Compresses packaging/man/dog-vision-web.1 into build/linux/dog-vision-web/dog-vision-web.1.gz."
    page = layout.projectDirectory.file("man/dog-vision-web.1")
    compressed = layout.buildDirectory.file("linux/$webPackage/dog-vision-web.1.gz")
}

val webTree = tasks.register<Sync>("dogVisionWebTree") {
    description = "Lays out in build/linux/dog-vision-web/tree the files its deb and its rpm install: the page, its " +
        "command and the command's manual page, the desktop entry and the icons."
    into(layout.buildDirectory.dir("linux/$webPackage/tree"))
    from(layout.projectDirectory.file("web/$webPackage")) {
        into("usr/bin")
        filePermissions { unix("rwxr-xr-x") }
    }
    from(webManPage) { into("usr/share/man/man1") }
    from(webPageFiles) {
        into(webHome.removePrefix("/"))
        exclude("*.map")
    }
    from(webDesktopEntry) { into("usr/share/applications") }
    val icons = rootProject.layout.projectDirectory.dir("gui-compose/packaging")
    from(icons.file("dog-vision.png")) {
        into("usr/share/icons/hicolor/256x256/apps")
        rename("dog-vision.png", "$webApplicationId.png")
    }
    from(icons.file("dog-vision.svg")) {
        into("usr/share/icons/hicolor/scalable/apps")
        rename("dog-vision.svg", "$webApplicationId.svg")
    }
}

/** What the deb and the rpm are listed with, in a package manager's search and its details. */
val webSummary = "How a dog or another animal sees colours (web page)"
val webDescription = """
    dog-vision shows a photo, a video or the camera with the colours a dog,
    a cat or another animal can tell apart, beside the original.

    This package is the web page, which the system's web browser shows from
    the installed files: the page fetches nothing.

    The desktop windows, which need no browser, are the packages
    dog-vision-compose and dog-vision-swing.
""".trimIndent()

val webLicences = thirdPartyLicenses("dogVisionWebLicences", emptyList()) {
    artifactName = "The package $webPackage"
    extraParts = webParts("dog-vision.js")
}

val packageWebDeb = tasks.register<DebPackage>("packageDogVisionWebDeb") {
    description = "Packs build/distributions/dog-vision-web_<version>_all.deb."
    group = "distribution"
    tree = layout.dir(webTree.map { it.destinationDir })
    packageName = webPackage
    architecture = "all"
    summary = webSummary
    longDescription = webDescription
    // xdg-open, which its command runs; the browser is the user's.
    depends = listOf("xdg-utils")
    recommends = emptyList()
    license = webLicences.flatMap { it.copyright }
}
artifact(packageWebDeb)

val packageWebRpm = tasks.register<RpmPackage>("packageDogVisionWebRpm") {
    description = "Packs build/distributions/dog-vision-web-<version>-1.noarch.rpm."
    group = "distribution"
    tree = layout.dir(webTree.map { it.destinationDir })
    packageName = webPackage
    architecture = "noarch"
    summary = webSummary
    longDescription = webDescription
    // As the deb's.
    requires = listOf("xdg-utils")
    recommends = emptyList()
    licenseName = webLicences.flatMap { it.spdx }.map { it.asFile.readText() }
    thirdPartyLicenses = webLicences.flatMap { it.notices }
}
artifact(packageWebRpm)

// The deb and the rpm, dog-vision: the files of each package above,
// where that package installs them, in one, for the command line, both windows and the page at once.
// It leaves out the source map's, whose package installs beside it as beside dog-vision-web.
// As its files are theirs, it conflicts with each of those packages, and each with it,
// so that a package manager removes the one to install the other.
// The debs replace one another as well,
// as Debian Policy 7.6.2 has it for a package that takes another's place, so that dpkg hands the files over.
// The rpm does not obsolete them, which would replace them with it on every update.
val allInOnePackage = "dog-vision"
val heldPackages = listOf(commonPackage, cliPackage, composePackage, swingPackage, webPackage)

/** The packages each package conflicts with, by its name: dog-vision with each it holds, and each of those with it. */
val conflicting = heldPackages.associateWith { listOf(allInOnePackage) } + (allInOnePackage to heldPackages)
tasks.withType<DebPackage>().configureEach {
    // A local copy, as the configuration cache cannot store the script a lambda reads a property of.
    val table = conflicting
    conflicts = packageName.map { table[it].orEmpty() }
    replaces = packageName.map { table[it].orEmpty() }
}
tasks.withType<RpmPackage>().configureEach {
    val table = conflicting
    conflicts = packageName.map { table[it].orEmpty() }
}

val allInOneTree = tasks.register<Sync>("dogVisionTree") {
    description = "Lays out in build/linux/dog-vision/tree the files its deb and its rpm install: those of " +
        heldPackages.joinToString(", ") + "."
    into(layout.buildDirectory.dir("linux/$allInOnePackage/tree"))
    from(commonTree, cliTree, composeTree, swingTree, webTree)
    // Each package's files are in folders or under names of its own, so none is two packages';
    // were one to be, the build fails rather than install either.
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

val allInOneDebDepends = tasks.register<DebDepends>("dogVisionDebDepends") {
    description = "Writes its deb's Depends into build/linux/dog-vision/debDepends.txt: those of both windows' " +
        "debs, but dog-vision-common, and xdg-utils, the page's."
    image = layout.dir(allInOneTree.map { it.destinationDir })
    packages = debianPackages
    // A Java that can open a window serves the command line too, and xdg-open opens the page.
    others = windowDebDepends + "xdg-utils"
    depends = layout.buildDirectory.file("linux/$allInOnePackage/debDepends.txt")
}

val allInOneRpmLibraryRequires = tasks.register<RpmLibraryRequires>("dogVisionRpmLibraryRequires") {
    description = "Lists in build/linux/dog-vision/rpmLibraryRequires.txt the libraries both windows' natives link " +
        "against and do not bring themselves, which its rpm requires."
    image = layout.dir(allInOneTree.map { it.destinationDir })
    requires = layout.buildDirectory.file("linux/$allInOnePackage/rpmLibraryRequires.txt")
}

/** What the deb and the rpm are listed with, in a package manager's search and its details. */
val allInOneSummary = "How a dog or another animal sees colours (all in one)"
val allInOneDescription = """
    dog-vision shows a photo, a video or the camera with the colours a dog,
    a cat or another animal can tell apart, beside the original.

    This package is the command line, both desktop windows and the web page
    in one: what the packages dog-vision-cli, dog-vision-compose,
    dog-vision-swing, dog-vision-web and dog-vision-common install, in their
    place. It cannot be installed beside any of them.
""".trimIndent()

// What the five packages hold that is not this project's own, together.
val allInOneLicences = thirdPartyLicenses(
    "dogVisionLicences",
    listOf(commonJars, composeJars, swingJars),
    unpacksNatives = true,
) {
    artifactName = "The package $allInOnePackage"
    extraParts = webParts("dog-vision.js")
}

val packageAllInOneDeb = tasks.register<DebPackage>("packageDogVisionDeb") {
    description = "Packs build/distributions/dog-vision_<version>_amd64.deb, on the system's Java."
    group = "distribution"
    tree = layout.dir(allInOneTree.map { it.destinationDir })
    packageName = allInOnePackage
    architecture = "amd64"
    summary = allInOneSummary
    longDescription = allInOneDescription
    depends = allInOneDebDepends.flatMap { it.depends }.map { it.asFile.readText().split(", ") }
    recommends = emptyList()
    license = allInOneLicences.flatMap { it.copyright }
    links = manPageLink(cliPackage) + manPageLink(composePackage) + manPageLink(swingPackage)
    lintianOverrides = lintianOverrides(allInOnePackage, "dog-vision-*", composePackage)
}
artifact(packageAllInOneDeb)

val packageAllInOneRpm = tasks.register<RpmPackage>("packageDogVisionRpm") {
    description = "Packs build/distributions/dog-vision-<version>-1.x86_64.rpm, on the system's Java."
    group = "distribution"
    tree = layout.dir(allInOneTree.map { it.destinationDir })
    packageName = allInOnePackage
    architecture = "x86_64"
    summary = allInOneSummary
    longDescription = allInOneDescription
    // The folders each package it holds owns, as /usr/share/dog-vision-common, rather than those named after it alone.
    folderNames = heldPackages
    // What both windows' rpms require, but dog-vision-common, and xdg-utils, as the deb's.
    val others = windowRpmRequires + swingRpmRequires + "xdg-utils"
    requires = allInOneRpmLibraryRequires.flatMap { it.requires }.map { file ->
        file.asFile.readText().split(',') + others
    }
    recommends = emptyList()
    links = manPageLink(cliPackage) + manPageLink(composePackage) + manPageLink(swingPackage)
    licenseName = allInOneLicences.flatMap { it.spdx }.map { it.asFile.readText() }
    thirdPartyLicenses = allInOneLicences.flatMap { it.notices }
}
artifact(packageAllInOneRpm)

// The deb and the rpm, dog-vision-web-sourcemap: the script's source map alone,
// beside the script that dog-vision-web or dog-vision installs,
// so that a browser's developer tools show the Kotlin sources' lines.
// It is for debugging, and larger than the script, so the page's own packages leave it out.
val webSourceMapPackage = "dog-vision-web-sourcemap"

val webSourceMapTree = tasks.register<Sync>("dogVisionWebSourcemapTree") {
    description = "Lays out in build/linux/dog-vision-web-sourcemap/tree the file its deb and its rpm install: the " +
        "script's source map."
    into(layout.buildDirectory.dir("linux/$webSourceMapPackage/tree"))
    from(webPageFiles) {
        into(webHome.removePrefix("/"))
        include("*.map")
    }
    val map = destinationDir.resolve(webHome.removePrefix("/") + "/dog-vision.js.map")
    doLast { check(map.isFile) { "web hands over no dog-vision.js.map" } }
}

val webSourceMapSummary = "How a dog or another animal sees colours (web page source map)"
val webSourceMapDescription = """
    dog-vision shows a photo, a video or the camera with the colours a dog,
    a cat or another animal can tell apart, beside the original.

    This package is the source map of the script of the page that
    dog-vision-web or dog-vision installs, with which a web browser's
    developer tools show the lines of the Kotlin sources the script was
    compiled from.
""".trimIndent()

val webSourceMapLicences = thirdPartyLicenses("dogVisionWebSourcemapLicences", emptyList()) {
    artifactName = "The package $webSourceMapPackage"
    extraParts = webParts("dog-vision.js.map")
}

val packageWebSourceMapDeb = tasks.register<DebPackage>("packageDogVisionWebSourcemapDeb") {
    description = "Packs build/distributions/dog-vision-web-sourcemap_<version>_all.deb."
    group = "distribution"
    tree = layout.dir(webSourceMapTree.map { it.destinationDir })
    packageName = webSourceMapPackage
    architecture = "all"
    summary = webSourceMapSummary
    longDescription = webSourceMapDescription
    // The script of its own version, which the map describes, from either package that installs it.
    depends = version.map { listOf("$webPackage (= $it) | $allInOnePackage (= $it)") }
    recommends = emptyList()
    license = webSourceMapLicences.flatMap { it.copyright }
}
artifact(packageWebSourceMapDeb)

val packageWebSourceMapRpm = tasks.register<RpmPackage>("packageDogVisionWebSourcemapRpm") {
    description = "Packs build/distributions/dog-vision-web-sourcemap-<version>-1.noarch.rpm."
    group = "distribution"
    tree = layout.dir(webSourceMapTree.map { it.destinationDir })
    packageName = webSourceMapPackage
    architecture = "noarch"
    summary = webSourceMapSummary
    longDescription = webSourceMapDescription
    // As the deb's.
    requires = version.zip(release) { version, release ->
        listOf("($webPackage = $version-$release or $allInOnePackage = $version-$release)")
    }
    recommends = emptyList()
    licenseName = webSourceMapLicences.flatMap { it.spdx }.map { it.asFile.readText() }
    thirdPartyLicenses = webSourceMapLicences.flatMap { it.notices }
}
artifact(packageWebSourceMapRpm)
