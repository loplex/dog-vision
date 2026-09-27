import cz.loplex.dogvision.packaging.AppImage
import cz.loplex.dogvision.packaging.DebDepends
import cz.loplex.dogvision.packaging.DebPackage
import cz.loplex.dogvision.packaging.DesktopEntry
import cz.loplex.dogvision.packaging.JavaLauncher
import cz.loplex.dogvision.packaging.NativesOnly
import cz.loplex.dogvision.packaging.RpmLibraryRequires
import cz.loplex.dogvision.packaging.RpmPackage
import cz.loplex.dogvision.packaging.UnpackNatives
import cz.loplex.dogvision.packaging.debianPackages
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The desktop window in Swing, with FlatLaf, for Linux and Windows: desktop-core's picture of a photo, a video or the
// camera, with Swing's own controls beside it, as the Compose window has them, and without Compose or skiko. Its main
// is the command line's as well, so that a photo given alone is converted as cli converts it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("cz.loplex.dogvision.packaging")
}

/** The class whose main the window and its JARs start with. */
val mainClassName = "cz.loplex.dogvision.swing.MainKt"

/** Whether this machine is Windows, where the run draws through ANGLE and WGL, as the window does. */
val onWindows = System.getProperty("os.name").startsWith("Windows")

/** LWJGL's native part for this machine: Linux's or Windows's, on x86-64. */
val lwjglNatives = if (onWindows) "natives-windows" else "natives-linux"

/** The LWJGL modules with a native part on Linux. */
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
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass = mainClassName
                applicationName = "dog-vision-swing"
            }
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":desktop-core"))
            implementation(libs.flatlaf)
            implementation(libs.kotlinx.coroutines.swing)
            // This machine's natives, which the run and linuxUberJar take.
            for (library in lwjglWithNatives) {
                runtimeOnly("${library.get().module}:${libs.versions.lwjgl.get()}:$lwjglNatives")
            }
            if (onWindows) runtimeOnly(libs.nucleus.angle.natives)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

/** What runs the window on Windows on x86-64 in place of this machine's natives: LWJGL's for it, and ANGLE. */
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
    for (library in lwjglWithWindowsNatives) {
        windowsRuntime("${library.get().module}:${libs.versions.lwjgl.get()}:natives-windows")
    }
    windowsRuntime(libs.nucleus.angle.natives)
}

/** The packages' version, as the other modules' packages have it. */
val packageVersion = providers.gradleProperty("appVersion")

/** The Compose window's icons and its second launcher's properties, which this window's packages take as they are. */
val packaging = rootProject.layout.projectDirectory.dir("desktop/packaging")

/** The JARs the window runs from: its own, and everything it needs, this machine's natives among them. */
val runtimeJars = files(tasks.named<Jar>("jvmJar"), configurations.named("jvmRuntimeClasspath"))

/** An uber JAR of [classpath]'s JARs with the window's own, which runs alone on a JDK 17 or newer. */
fun Jar.uberJar(fileName: String, classpath: Provider<out Iterable<File>>) {
    archiveFileName = fileName
    destinationDirectory = layout.buildDirectory.dir("jars")
    manifest { attributes("Main-Class" to mainClassName) }
    from(tasks.named<Jar>("jvmJar").map { zipTree(it.archiveFile) })
    from(classpath.map { jars -> jars.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val excludes = listOf("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
    exclude(excludes)
    inputs.property("excludes", excludes)
}

tasks.register<Jar>("linuxUberJar") {
    description = "Assembles build/jars/dog-vision-swing-linux-x64-<version>.jar, the window for this machine."
    group = "distribution"
    uberJar("dog-vision-swing-linux-x64-${packageVersion.get()}.jar", configurations.named("jvmRuntimeClasspath"))
}

// Built on any machine: Windows's natives and ANGLE in place of this machine's.
tasks.register<Jar>("windowsUberJar") {
    description = "Assembles build/jars/dog-vision-swing-windows-x64-<version>.jar, the window for Windows."
    group = "distribution"
    uberJar("dog-vision-swing-windows-x64-${packageVersion.get()}.jar", configurations.named("windowsRuntime"))
    // ANGLE for Windows on ARM, which LWJGL's natives here are not for.
    exclude("nucleus/native/win32-aarch64/**")
}

/**
 * The JDK the tar.gz's runtime is linked from and jpackage runs from: Temurin, as the Compose window's build says why.
 * Gradle downloads it where this machine has none.
 */
val packagingJdk = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.ADOPTIUM
}

// jpackage's app image with a runtime of its own, and dog-vision-cli beside dog-vision-swing, as the Compose window's.
val appImage = tasks.register<AppImage>("appImage") {
    description = "Makes build/app-image/dog-vision-swing, jpackage's app image with a runtime of its own."
    group = "distribution"
    jdkHome = packagingJdk.map { it.metadata.installationPath.asFile.path }
    jars.from(runtimeJars)
    mainJar = tasks.named<Jar>("jvmJar").flatMap { it.archiveFileName }
    mainClass = mainClassName
    imageName = "dog-vision-swing"
    appVersion = packageVersion
    // What jdeps --print-module-deps finds the JARs using.
    modules = listOf("java.base", "java.desktop", "java.instrument", "jdk.unsupported")
    javaOptions = emptyList()
    icon = packaging.file("dog-vision.png")
    launchers = mapOf("dog-vision-cli" to packaging.file("dog-vision-cli.properties").asFile)
    destination = layout.buildDirectory.dir("app-image")
    input = layout.buildDirectory.dir("app-image-input")
}

// The app image as it is, to unpack and run anywhere on Linux on x86-64 without installing it.
tasks.register<Tar>("packageTarGz") {
    description = "Packs the app image into build/packages/tar/dog-vision-swing-<version>-linux-x64.tar.gz."
    group = "distribution"
    archiveFileName = "dog-vision-swing-${packageVersion.get()}-linux-x64.tar.gz"
    destinationDirectory = layout.buildDirectory.dir("packages/tar")
    compression = Compression.GZIP
    // The launchers and the runtime's jspawnhelper, which starts ffmpeg, stay executable.
    eachFile { permissions { unix(if (file.canExecute()) "755" else "644") } }
    from(appImage.flatMap { it.destination })
}

// The deb and the rpm, dog-vision-swing, on the system's Java, as the Compose window's dog-vision: the JARs in
// /usr/share/dog-vision-swing/lib, LWJGL's natives in /usr/lib/dog-vision-swing, a launcher in /usr/bin, and the
// desktop entry and icons under names of their own, so that both windows install side by side.
val linuxPackage = "dog-vision-swing"
val linuxHome = "/usr/share/$linuxPackage"
val linuxNativesHome = "/usr/lib/$linuxPackage"

/** The desktop entry's and the icons' name, which the Compose window's cz.loplex.dogvision is not. */
val applicationId = "cz.loplex.dogvision.swing"

// LWJGL's natives, which it would otherwise unpack at run time into the user's home or /tmp. FlatLaf's are left in its
// JAR: it loads them on Linux only for window decorations of its own, which the window does not use, and they link
// GTK 3.
val linuxNatives = tasks.register<UnpackNatives>("linuxNatives") {
    jars.from(runtimeJars.filter { !it.name.startsWith("flatlaf-") })
    natives = layout.buildDirectory.dir("packages/natives")
}

val linuxLauncher = tasks.register<JavaLauncher>("linuxLauncher") {
    commandName = linuxPackage
    mainClass = mainClassName
    jars.from(runtimeJars)
    jarDirectory = "$linuxHome/lib"
    jvmOptions = listOf("-Dorg.lwjgl.librarypath=$linuxNativesHome")
    minimumJava = 17
    script = layout.buildDirectory.file("packages/launcher/$linuxPackage")
}

val linuxDesktopEntry = tasks.register<DesktopEntry>("linuxDesktopEntry") {
    strings = rootProject.layout.projectDirectory.dir("texts/strings")
    nameString = "app_name"
    nameSuffix = " (Swing)"
    comment = "How a dog or a cat sees a photo, a video or the camera"
    exec = linuxPackage
    icon = applicationId
    categories = listOf("Graphics")
    entry = layout.buildDirectory.file("packages/$applicationId.desktop")
}

val linuxTree = tasks.register<Sync>("linuxTree") {
    into(layout.buildDirectory.dir("packages/tree"))
    from(runtimeJars) {
        into(linuxHome.removePrefix("/") + "/lib")
        exclude(NativesOnly)
    }
    from(linuxNatives) { into(linuxNativesHome.removePrefix("/")) }
    from(linuxLauncher) { into("usr/bin") }
    from(linuxDesktopEntry) { into("usr/share/applications") }
    from(packaging.file("dog-vision.png")) {
        into("usr/share/icons/hicolor/256x256/apps")
        rename("dog-vision.png", "$applicationId.png")
    }
    from(packaging.file("dog-vision.svg")) {
        into("usr/share/icons/hicolor/scalable/apps")
        rename("dog-vision.svg", "$applicationId.svg")
    }
}

val rpmLibraryRequires = tasks.register<RpmLibraryRequires>("rpmLibraryRequires") {
    image = linuxNatives.flatMap { it.natives }
    requires = layout.buildDirectory.file("packages/rpmLibraryRequires.txt")
}

val debDepends = tasks.register<DebDepends>("debDepends") {
    image = linuxNatives.flatMap { it.natives }
    packages = debianPackages
    // As the Compose window's: a Java that can open a window, libEGL, which LWJGL opens once it runs, and ffmpeg.
    others = listOf("default-jre (>= 2:1.17) | java17-runtime", "libegl1", "ffmpeg")
    depends = layout.buildDirectory.file("packages/debDepends.txt")
}

/** What the deb and the rpm are listed with, in a package manager's search and its details. */
val linuxSummary = "How a dog or a cat sees a photo, a video or the camera, in Swing"
val linuxDescription = """
    Shows a photo, a video or the camera with the colours a dog, a cat or
    another animal can tell apart, beside the original, in a Swing window,
    which needs neither Compose nor skiko. The package dog-vision is the
    same window in Compose.

    Given a photo alone, it converts it as the command line does, which is
    the package dog-vision-cli.
""".trimIndent()

tasks.register<DebPackage>("packageDeb") {
    description = "Packs build/packages/deb/dog-vision-swing_<version>_amd64.deb, on the system's Java."
    group = "distribution"
    tree = layout.dir(linuxTree.map { it.destinationDir })
    packageName = linuxPackage
    architecture = "amd64"
    summary = linuxSummary
    longDescription = linuxDescription
    depends = debDepends.flatMap { it.depends }.map { it.asFile.readText().split(", ") }
    recommends = listOf("dog-vision-cli")
}

tasks.register<RpmPackage>("packageRpm") {
    description = "Packs build/packages/rpm/dog-vision-swing-<version>-1.x86_64.rpm, on the system's Java."
    group = "distribution"
    tree = layout.dir(linuxTree.map { it.destinationDir })
    packageName = linuxPackage
    architecture = "x86_64"
    summary = linuxSummary
    longDescription = linuxDescription
    // As the Compose window's rpm has them, and a font, without which Swing cannot start: openSUSE's JRE brings none,
    // where Debian's fontconfig does. Fedora's and Rocky's font packages provide font(:lang=en), which openSUSE's do
    // not, so it names DejaVu there.
    requires = rpmLibraryRequires.flatMap { it.requires }.map { file ->
        file.asFile.readText().split(',') +
            listOf("/bin/sh", "(jre-17 or jre-21 or jre-25)", "libEGL.so.1()(64bit)", "/usr/bin/ffmpeg") +
            "(font(:lang=en) or dejavu-fonts)"
    }
    recommends = listOf("dog-vision-cli")
}
