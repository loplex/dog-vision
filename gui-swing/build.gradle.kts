import cz.loplex.dogvision.packaging.AppImage
import cz.loplex.dogvision.packaging.artifact
import cz.loplex.dogvision.packaging.glNatives
import cz.loplex.dogvision.packaging.packagingJdk
import cz.loplex.dogvision.packaging.uberJar
import cz.loplex.dogvision.packaging.uberJarLicences
import cz.loplex.dogvision.packaging.windowsLauncher
import cz.loplex.dogvision.packaging.windowsRuntime
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The desktop window in Swing, with FlatLaf, for Linux and Windows:
// gui-core's picture of a photo, a video or the camera, with Swing's own controls beside it,
// as the Compose window has them, and without Compose or skiko.
// It takes gui-core's options of a window, as the Compose window does.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    id("cz.loplex.dogvision.packaging")
}

/** The class whose main the window and its JARs start with. */
val mainClassName = "cz.loplex.dogvision.swing.MainKt"

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
            implementation(project(":gui-core"))
            implementation(libs.flatlaf)
            implementation(libs.kotlinx.coroutines.swing)
            // This machine's natives, which the run and linuxUberJar take.
            for (natives in glNatives()) runtimeOnly(natives)
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

// What runs the window on Windows on x86-64 in place of this machine's natives: LWJGL's for it, and ANGLE.
// Only windowsUberJar and windowsLauncher take it.
windowsRuntime()

/** The packages' version, as the other modules' packages have it. */
val packageVersion = providers.gradleProperty("appVersion")

/** The Compose window's icons and its second launcher's properties, which this window's packages take as they are. */
val packaging = rootProject.layout.projectDirectory.dir("gui-compose/packaging")

/** The JARs the window runs from: its own, and everything it needs, this machine's natives among them. */
val runtimeJars = files(tasks.named<Jar>("jvmJar"), configurations.named("jvmRuntimeClasspath"))

/** The window's own JAR, which each uber JAR below merges with the JARs the window needs. */
val windowJar = tasks.named<Jar>("jvmJar").flatMap { it.archiveFile }

/** ANGLE for Windows on ARM, which LWJGL's natives here are not for. */
val armAngle = "nucleus/native/win32-aarch64"

val linuxUberJarNotices =
    uberJarLicences(
        "linuxUberJarLicences",
        "dog-vision-swing-linux-x64-${packageVersion.get()}.jar",
        configurations.named("jvmRuntimeClasspath"),
    )
val linuxUberJar = tasks.register<Jar>("linuxUberJar") {
    description = "Assembles build/jars/dog-vision-swing-linux-x64-<version>.jar, the window for this machine."
    group = "distribution"
    destinationDirectory = layout.buildDirectory.dir("jars")
    uberJar(
        "dog-vision-swing-linux-x64-${packageVersion.get()}.jar",
        mainClassName,
        windowJar,
        configurations.named("jvmRuntimeClasspath"),
        linuxUberJarNotices,
    )
}
artifact(linuxUberJar)

// Built on any machine: Windows's natives and ANGLE in place of this machine's.
val windowsUberJarNotices =
    uberJarLicences(
        "windowsUberJarLicences",
        "dog-vision-swing-windows-x64-${packageVersion.get()}.jar",
        configurations.named("windowsRuntime"),
    )
val windowsUberJar = tasks.register<Jar>("windowsUberJar") {
    description = "Assembles build/jars/dog-vision-swing-windows-x64-<version>.jar, the window for Windows."
    group = "distribution"
    destinationDirectory = layout.buildDirectory.dir("jars")
    uberJar(
        "dog-vision-swing-windows-x64-${packageVersion.get()}.jar",
        mainClassName,
        windowJar,
        configurations.named("windowsRuntime"),
        windowsUberJarNotices,
        excludes = listOf("$armAngle/**"),
    )
}
artifact(windowsUberJar)

/** The JDK the tar.gz's runtime is linked from and jpackage runs from, Temurin, which build-logic says why. */
val packagingJdk = packagingJdk()

/** The modules of the tar.gz's runtime and the MSI's: what jdeps --print-module-deps finds the JARs using. */
val runtimeModules = listOf("java.base", "java.desktop", "java.instrument", "jdk.unsupported")

// dog-vision-swing.exe beside the Compose window's dog-vision-compose.exe in the MSI,
// which :packaging takes, on the JARs windowsUberJar merges.
windowsLauncher(
    name = "dog-vision-swing",
    ownJar = windowJar,
    classpath = configurations.named("windowsRuntime"),
    mainClass = mainClassName,
    runtimeModules = runtimeModules,
    leftOut = listOf(armAngle),
)

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
    modules = runtimeModules
    javaOptions = emptyList()
    icon = packaging.file("dog-vision.png")
    launchers = mapOf("dog-vision-cli" to packaging.file("dog-vision-cli.properties").asFile)
    destination = layout.buildDirectory.dir("app-image")
    input = layout.buildDirectory.dir("app-image-input")
}

// The app image as it is, to unpack and run anywhere on Linux on x86-64 without installing it.
val packageTarGz = tasks.register<Tar>("packageTarGz") {
    description = "Packs the app image into build/packages/tar/dog-vision-swing-<version>-linux-x64.tar.gz."
    group = "distribution"
    archiveFileName = "dog-vision-swing-${packageVersion.get()}-linux-x64.tar.gz"
    destinationDirectory = layout.buildDirectory.dir("packages/tar")
    compression = Compression.GZIP
    // The launchers and the runtime's jspawnhelper, which starts ffmpeg, stay executable.
    eachFile { permissions { unix(if (file.canExecute()) "755" else "644") } }
    from(appImage.flatMap { it.destination })
}
artifact(packageTarGz)
