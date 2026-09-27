import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

// The desktop window in Compose Multiplatform, for Linux and Windows: a photo, a video or the camera through ffmpeg,
// rendered by gl's passes in an offscreen GL context, with ui's controls beside it. Its main is the command line's as
// well, so that a photo given alone is converted as cli converts it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/** The class whose main the window and its JARs start with. */
val mainClassName = "cz.loplex.dogvision.desktop.MainKt"

/** Whether this machine is Windows, where the run and the tests draw through ANGLE and WGL, as the window does. */
val onWindows = System.getProperty("os.name").startsWith("Windows")

/** LWJGL's native part for this machine: Linux's or Windows's, on x86-64. */
val lwjglNatives = if (onWindows) "natives-windows" else "natives-linux"

/** The LWJGL modules with a native part on Linux: desktop GL's is for the tests of LwjglGl through EGL. */
val lwjglWithLinuxNatives = listOf(libs.lwjgl.asProvider(), libs.lwjgl.opengles, libs.lwjgl.opengl)

/** The LWJGL modules with a native part on Windows, where GLFW makes the WGL context. */
val lwjglWithWindowsNatives = lwjglWithLinuxNatives + libs.lwjgl.glfw

/** The LWJGL modules with a native part on this machine. */
val lwjglWithNatives = if (onWindows) lwjglWithWindowsNatives else lwjglWithLinuxNatives

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":cli"))
            implementation(project(":gl"))
            implementation(project(":ui"))
            implementation(libs.compose.multiplatform.desktop)
            implementation(libs.compose.multiplatform.material3)
            implementation(libs.lwjgl.asProvider())
            implementation(libs.lwjgl.egl)
            implementation(libs.lwjgl.glfw)
            implementation(libs.lwjgl.opengl)
            implementation(libs.lwjgl.opengles)
            // This machine's natives, which the run, the tests and packageUberJarForCurrentOS take.
            runtimeOnly(compose.desktop.currentOs)
            for (library in lwjglWithNatives) {
                runtimeOnly("${library.get().module}:${libs.versions.lwjgl.get()}:$lwjglNatives")
            }
            if (onWindows) runtimeOnly(libs.nucleus.angle.natives)
        }
        jvmTest.dependencies {
            implementation(project(":testing"))
            implementation(kotlin("test"))
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

/**
 * What runs the window on Windows on x86-64 in place of this machine's natives: Compose's and LWJGL's natives for it,
 * and ANGLE, OpenGL ES over Direct3D 11, which Windows has no EGL for. Only windowsUberJar takes it.
 */
val windowsRuntime: Configuration = configurations.create("windowsRuntime") {
    isCanBeConsumed = false
    extendsFrom(configurations["commonMainImplementation"], configurations["jvmMainImplementation"])
    // The attributes the JVM target resolves with, so that the other modules give their JVM variants.
    val jvmAttributes = configurations["jvmRuntimeClasspath"].attributes
    attributes {
        for (key in jvmAttributes.keySet()) copyAttribute(key, jvmAttributes)
    }
}

fun <T : Any> AttributeContainer.copyAttribute(key: Attribute<T>, from: AttributeContainer) {
    attribute(key, checkNotNull(from.getAttribute(key)))
}

dependencies {
    windowsRuntime(libs.compose.multiplatform.desktop.windows.x64)
    for (library in lwjglWithWindowsNatives) {
        windowsRuntime("${library.get().module}:${libs.versions.lwjgl.get()}:natives-windows")
    }
    windowsRuntime(libs.nucleus.angle.natives)
}

/** The icons, the second launcher's properties and what else the packages take. */
val packaging = layout.projectDirectory.dir("packaging")

/**
 * The JDK the Linux packages' runtime is linked from and jpackage runs from: Temurin, which brings its own libjpeg,
 * giflib, libpng, lcms2, HarfBuzz and FreeType, where a distribution's OpenJDK, Ubuntu's among them, links the
 * system's, and the packages would then need that distribution's. Gradle downloads it where this machine has none.
 */
val packagingJdk = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.ADOPTIUM
}

// packageUberJarForCurrentOS writes build/compose/jars/dog-vision-linux-x64-<version>.jar, which runs alone on a JDK
// 17 or newer, with this machine's natives in it. packageDeb and packageRpm write jpackage's packages for Linux, with a
// runtime of their own, under build/compose/binaries/main/{deb,rpm}; Windows's MSI is tools/package_msi_on_linux.sh's.
compose.desktop {
    application {
        mainClass = mainClassName
        javaHome = packagingJdk.get().metadata.installationPath.asFile.path
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "dog-vision"
            packageVersion = "0.1.0" // the Android app's versionName
            description = "How a dog or a cat sees a photo, a video or the camera"
            vendor = "Martin Lopatář"
            licenseFile = rootProject.file("LICENSE")
            // Beyond the modules Compose always takes: what suggestRuntimeModules finds the classes using.
            modules("java.instrument", "jdk.unsupported")
            linux {
                iconFile = packaging.file("dog-vision.png")
                shortcut = true
                menuGroup = "Graphics"
                appCategory = "graphics"
                debMaintainer = "lopin.git@loplex.cz"
                rpmLicenseType = "GPL-3.0-or-later"
            }
        }
    }
}

/**
 * Lists the libraries an app image's runtime and natives link against, as rpm's own generator, elfdeps, names them for
 * an rpm's requirements (libX11.so.6()(64bit) and the like), less those the image brings itself. jpackage asks the
 * build machine's rpm database which packages own them, which off an rpm-based system finds none.
 */
abstract class RpmLibraryRequires : DefaultTask() {
    @get:InputDirectory
    abstract val image: DirectoryProperty

    /** The requirements, joined by commas as jpackage's --linux-package-deps takes them. */
    @get:OutputFile
    abstract val requires: RegularFileProperty

    @TaskAction
    fun list() {
        val elfMagic = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        val elfFiles = image.get().asFile.walk()
            .filter { file -> file.isFile && file.inputStream().use { it.readNBytes(4) }.contentEquals(elfMagic) }
            .joinToString("") { it.path + "\n" } // elfdeps fails on a last line without its line feed
        val provided = elfdeps("--provides", elfFiles).map { it.substringBefore('(') }.toSet()
        val required = elfdeps("--requires", elfFiles).filter { it.substringBefore('(') !in provided }
        requires.get().asFile.writeText(required.distinct().sorted().joinToString(","))
    }

    private fun elfdeps(mode: String, files: String): List<String> {
        val rpmConfigDir = ProcessBuilder("rpm", "--eval", "%{_rpmconfigdir}").start().inputReader().readText().trim()
        val process = ProcessBuilder("$rpmConfigDir/elfdeps", mode).redirectErrorStream(true).start()
        process.outputWriter().use { it.write(files) }
        val lines = process.inputReader().readLines()
        check(process.waitFor() == 0) { "elfdeps $mode failed: $lines" }
        return lines
    }
}

val rpmLibraryRequires = tasks.register<RpmLibraryRequires>("rpmLibraryRequires") {
    image = tasks.named<AbstractJPackageTask>("createDistributable").flatMap { it.destinationDir }
    requires = layout.buildDirectory.file("compose/tmp/rpmLibraryRequires.txt")
}

/**
 * The Debian package of each library the app image links against, by soname, as Ubuntu 20.04 and Debian 11 name them:
 * later releases keep the names or provide them (Ubuntu 24.04's libasound2t64 provides libasound2). debDepends fails on
 * a library missing here.
 */
val debianPackages = mapOf(
    "ld-linux-x86-64.so.2" to "libc6",
    "libc.so.6" to "libc6",
    "libdl.so.2" to "libc6",
    "libm.so.6" to "libc6",
    "libpthread.so.0" to "libc6",
    "librt.so.1" to "libc6",
    "libasound.so.2" to "libasound2",
    "libfontconfig.so.1" to "libfontconfig1",
    "libGL.so.1" to "libgl1",
    "libstdc++.so.6" to "libstdc++6",
    "libX11.so.6" to "libx11-6",
    "libXext.so.6" to "libxext6",
    "libXi.so.6" to "libxi6",
    "libXrender.so.1" to "libxrender1",
    "libXtst.so.6" to "libxtst6",
)

/**
 * Writes the deb's Depends from the app image alone, so that it is the same wherever the deb is built: jpackage looks
 * the libraries up in the build machine's dpkg database, and so names its release's packages, such as Ubuntu 24.04's
 * libasound2t64, which older releases lack. Each library the image's ELF files need and do not bring is named by
 * [packages], libc6 with the newest glibc version they ask for; [others] follow.
 */
abstract class DebDepends : DefaultTask() {
    @get:InputDirectory
    abstract val image: DirectoryProperty

    @get:Input
    abstract val packages: MapProperty<String, String>

    @get:Input
    abstract val others: ListProperty<String>

    @get:OutputFile
    abstract val depends: RegularFileProperty

    @TaskAction
    fun write() {
        val elfMagic = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        val elfFiles = image.get().asFile.walk()
            .filter { file -> file.isFile && file.inputStream().use { it.readNBytes(4) }.contentEquals(elfMagic) }
            .toList()
        val dynamic = elfFiles.flatMap { readelf("-d", it) }
        fun tagged(tag: String) = dynamic.mapNotNull { Regex("""\($tag\).*\[(.+)]""").find(it)?.groupValues?.get(1) }
        val brought = tagged("SONAME").toSet() + elfFiles.map { it.name }
        val needed = tagged("NEEDED").toSet() - brought
        val table = packages.get()
        val unknown = needed - table.keys
        check(unknown.isEmpty()) { "debianPackages names no package for ${unknown.sorted()}" }
        val glibc = elfFiles.flatMap { readelf("-V", it) }
            .mapNotNull { Regex("""Name: GLIBC_([0-9.]+)""").find(it)?.groupValues?.get(1) }
            .maxWith(compareBy<String>({ it.split('.')[0].toInt() }, { it.split('.').getOrElse(1) { "0" }.toInt() }))
        val libraries = needed.map { table.getValue(it) }.distinct().sorted()
            .map { if (it == "libc6") "libc6 (>= $glibc)" else it }
        depends.get().asFile.writeText((libraries + others.get()).joinToString(", "))
    }

    private fun readelf(option: String, file: File): List<String> {
        val process = ProcessBuilder("readelf", option, file.path).redirectErrorStream(true)
            .apply { environment()["LC_ALL"] = "C" }.start()
        val lines = process.inputReader().readLines()
        check(process.waitFor() == 0) { "readelf $option $file failed: $lines" }
        return lines
    }
}

val debDepends = tasks.register<DebDepends>("debDepends") {
    image = tasks.named<AbstractJPackageTask>("createDistributable").flatMap { it.destinationDir }
    packages = debianPackages
    // What the image's ELF files do not name: LWJGL opens libEGL once it runs, and ffmpeg runs apart for a video or
    // the camera.
    others = listOf("libegl1", "ffmpeg")
    depends = layout.buildDirectory.file("compose/tmp/debDepends.txt")
}

/**
 * Makes each deb jpackage wrote to [debs] the one to ship, and packs it again with xz, which every dpkg reads, where
 * the build machine's dpkg-deb may choose zstd, which Debian 11's cannot:
 * - its Depends is [depends]';
 * - the window's desktop entry, [jpackageEntry] in the package's tree, is a file of the package at [entry], as Debian's
 *   packages ship theirs: dpkg makes its folder where it is missing and removes it with the package. jpackage's scripts
 *   install it and remove it with xdg-desktop-menu instead, which fails where the folder does not exist, as on a system
 *   with no desktop, and dpkg then leaves the package half-installed or half-removed.
 */
class RepackDeb(
    private val debs: Provider<Directory>,
    private val depends: Provider<RegularFile>,
    private val jpackageEntry: String,
    private val entry: String,
) : Action<Task> {
    override fun execute(task: Task) {
        for (deb in debs.get().asFile.listFiles { file -> file.extension == "deb" }.orEmpty()) {
            val tree = Files.createTempDirectory("deb").toFile()
            try {
                dpkgDeb("-R", deb.path, tree.path)
                val control = tree.resolve("DEBIAN/control")
                // In its place: the blank line jpackage ends the file with would end the stanza before it.
                val lines = control.readLines()
                    .map { if (it.startsWith("Depends:")) "Depends: ${depends.get().asFile.readText()}" else it }
                control.writeText(lines.joinToString("\n", postfix = "\n"))
                val desktopFile = tree.resolve(jpackageEntry)
                check(desktopFile.isFile) { "jpackage's deb has no $jpackageEntry" }
                val installed = tree.resolve(entry)
                // Each folder made here as Debian's are, rwxr-xr-x, whatever the build's umask.
                generateSequence(installed.parentFile) { it.parentFile }.takeWhile { it != tree }.toList().reversed()
                    .forEach { folder ->
                        folder.mkdir()
                        Files.setPosixFilePermissions(folder.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
                    }
                check(desktopFile.renameTo(installed)) { "Cannot move $jpackageEntry to $entry" }
                removeLine(tree.resolve("DEBIAN/postinst"), "xdg-desktop-menu install /$jpackageEntry")
                removeLine(
                    tree.resolve("DEBIAN/prerm"),
                    "do_if_file_belongs_to_single_package /$jpackageEntry xdg-desktop-menu uninstall /$jpackageEntry",
                )
                dpkgDeb("--root-owner-group", "-Zxz", "-b", tree.path, deb.path)
            } finally {
                tree.deleteRecursively()
            }
        }
    }

    /** Removes the one line of [script] that is [line], and fails where jpackage's script has none. */
    private fun removeLine(script: File, line: String) {
        val lines = script.readLines()
        check(lines.count { it.trim() == line } == 1) { "${script.name} has no line $line" }
        script.writeText(lines.filterNot { it.trim() == line }.joinToString("\n", postfix = "\n"))
    }

    private fun dpkgDeb(vararg arguments: String) {
        val process = ProcessBuilder("dpkg-deb", *arguments).redirectErrorStream(true).start()
        val output = process.inputReader().readText()
        check(process.waitFor() == 0) { "dpkg-deb ${arguments.joinToString(" ")} failed: $output" }
    }
}

class DeleteDirectory(private val directory: Provider<Directory>) : Action<Task> {
    override fun execute(task: Task) {
        directory.get().asFile.deleteRecursively()
    }
}

/**
 * Makes the rpm jpackage wrote to [rpms] the one to ship, by running rpmbuild again on the spec and the app image
 * jpackage left in [temp], its --temp, with the spec changed so that the window's desktop entry, [jpackageEntry] in the
 * image, is a file of the package at [entry], as with the deb, and xdg-utils is not required. jpackage's scriptlets
 * install and remove it with xdg-desktop-menu instead, which openSUSE's xdg-utils fails where /etc/xdg/menus does not
 * exist, as on a system with no desktop: the entry is not installed, and %preun fails, so rpm cannot remove the
 * package. jpackage cannot be given a spec of ours: it reads the last --resource-dir, and Compose passes its own after
 * freeArgs.
 */
class RepackRpm(
    private val rpms: Provider<Directory>,
    private val temp: Provider<Directory>,
    private val jpackageEntry: String,
    private val entry: String,
) : Action<Task> {
    override fun execute(task: Task) {
        val temp = temp.get().asFile
        val spec = temp.resolve("SPECS").listFiles { file -> file.extension == "spec" }.orEmpty().singleOrNull()
            ?: error("jpackage left no single spec in ${temp.resolve("SPECS")}")
        val rpm = rpms.get().asFile.listFiles { file -> file.extension == "rpm" }.orEmpty().singleOrNull()
            ?: error("jpackage wrote no single rpm to ${rpms.get()}")
        var lines = spec.readLines()
        lines = replaceLine(lines, { it.startsWith("Requires: xdg-utils ,") }) {
            listOf("Requires: " + it.substringAfter(',').trim())
        }
        lines = replaceLine(lines, { it.startsWith("cp -r %{_sourcedir}/") }) {
            listOf(
                it,
                "install -d -m 755 %{buildroot}/${entry.substringBeforeLast('/')}",
                "mv %{buildroot}/$jpackageEntry %{buildroot}/$entry",
            )
        }
        lines = replaceLine(lines, { it.trim() == "xdg-desktop-menu install /$jpackageEntry" }) { emptyList() }
        lines = replaceLine(
            lines,
            {
                it.trim() ==
                    "do_if_file_belongs_to_single_package /$jpackageEntry xdg-desktop-menu uninstall /$jpackageEntry"
            },
        ) { emptyList() }
        spec.writeText(lines.joinToString("\n", postfix = "\n"))
        // As jpackage runs it.
        val process = ProcessBuilder(
            "rpmbuild", "-bb", spec.path,
            "--define", "%_sourcedir ${temp.resolve("image")}",
            "--define", "%_rpmdir ${rpm.parent}",
            "--define", "%_topdir $temp",
            "--define", "%_rpmfilename ${rpm.name}",
        ).redirectErrorStream(true).start()
        val output = process.inputReader().readText()
        check(process.waitFor() == 0) { "rpmbuild failed: $output" }
    }

    /** Replaces the one line of [lines] that [matches] by [by]'s, and fails where jpackage's spec has none. */
    private fun replaceLine(lines: List<String>, matches: (String) -> Boolean, by: (String) -> List<String>) =
        lines.singleOrNull(matches)?.let { line -> lines.flatMap { if (it === line) by(it) else listOf(it) } }
            ?: error("jpackage's spec has no single line to replace")
}

// dog-vision-cli beside dog-vision, in the app image and in each package: Compose runs jpackage for each of them from
// the JARs, not the packages from the app image.
tasks.withType<AbstractJPackageTask>().configureEach {
    val launcher = packaging.file("dog-vision-cli.properties")
    freeArgs.addAll("--add-launcher", "dog-vision-cli=${launcher.asFile}")
    // freeArgs holds only its path, so that the packages are made again when the file changes.
    inputs.file(launcher)
    // The deb's Depends is debDepends'. The rpm adds what jpackage cannot find through ldd: LWJGL opens libEGL once it
    // runs, and ffmpeg runs apart for a video or the camera. It names what it needs rather than a package, as Fedora's
    // and openSUSE's names differ, and Fedora has two ffmpeg packages; it takes the libraries rpmLibraryRequires lists
    // as well. No spaces: Compose writes freeArgs into jpackage's argument file unquoted.
    when {
        name.endsWith("Deb") -> {
            inputs.files(debDepends)
            val packageName = compose.desktop.application.nativeDistributions.packageName
            doLast(
                RepackDeb(
                    destinationDir,
                    debDepends.flatMap { it.depends },
                    jpackageEntry = "opt/$packageName/lib/$packageName-$packageName.desktop",
                    entry = "usr/share/applications/cz.loplex.dogvision.desktop",
                ),
            )
        }

        name.endsWith("Rpm") -> {
            freeArgs.add("--linux-package-deps")
            val libraries = rpmLibraryRequires.flatMap { it.requires }.map { it.asFile.readText() }
            freeArgs.add(libraries.map { "$it,libEGL.so.1()(64bit),/usr/bin/ffmpeg" })
            // jpackage's --temp, which it wants empty, for RepackRpm to build the rpm again from.
            val temp = layout.buildDirectory.dir("compose/tmp/$name-jpackage")
            freeArgs.add("--temp")
            freeArgs.add(temp.map { it.asFile.path })
            doFirst(DeleteDirectory(temp))
            val packageName = compose.desktop.application.nativeDistributions.packageName
            doLast(
                RepackRpm(
                    destinationDir,
                    temp,
                    jpackageEntry = "opt/$packageName/lib/$packageName-$packageName.desktop",
                    entry = "usr/share/applications/cz.loplex.dogvision.desktop",
                ),
            )
        }
    }
}

// The app image as it is, to unpack and run anywhere on Linux on x86-64 without installing it.
tasks.register<Tar>("packageTarGz") {
    description = "Packs the app image into build/compose/binaries/main/tar/dog-vision-<version>-linux-x64.tar.gz."
    group = "compose desktop"
    val version = compose.desktop.application.nativeDistributions.packageVersion
    archiveFileName = "dog-vision-$version-linux-x64.tar.gz"
    destinationDirectory = layout.buildDirectory.dir("compose/binaries/main/tar")
    compression = Compression.GZIP
    // The launchers and the runtime's jspawnhelper, which starts ffmpeg, stay executable: Gradle's archives otherwise
    // give every file the same mode.
    eachFile { permissions { unix(if (file.canExecute()) "755" else "644") } }
    from(tasks.named<AbstractJPackageTask>("createDistributable").flatMap { it.destinationDir })
}

// Built on any machine, as jpackage's installers are not: Windows's natives and ANGLE in place of this machine's.
tasks.register<Jar>("windowsUberJar") {
    description = "Assembles build/compose/jars/dog-vision-windows-x64-<version>.jar, the window for Windows."
    group = "compose desktop"
    archiveFileName = "dog-vision-windows-x64-${compose.desktop.application.nativeDistributions.packageVersion}.jar"
    destinationDirectory = layout.buildDirectory.dir("compose/jars")
    manifest { attributes("Main-Class" to mainClassName) }
    from(tasks.named<Jar>("jvmJar").map { zipTree(it.archiveFile) })
    from(configurations.named("windowsRuntime").map { classpath -> classpath.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val excludes = listOf(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "**/module-info.class",
        // ANGLE for Windows on ARM, which LWJGL's and skiko's natives here are not for.
        "nucleus/native/win32-aarch64/**",
    )
    exclude(excludes)
    // Gradle fingerprints the zip files, not what the patterns leave of them, and would otherwise keep the JAR as it is
    // when a pattern changes.
    inputs.property("excludes", excludes)
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
