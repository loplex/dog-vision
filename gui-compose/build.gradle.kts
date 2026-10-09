import cz.loplex.dogvision.packaging.artifact
import cz.loplex.dogvision.packaging.glNatives
import cz.loplex.dogvision.packaging.packagingJdk
import cz.loplex.dogvision.packaging.uberJar
import cz.loplex.dogvision.packaging.uberJarLicences
import cz.loplex.dogvision.packaging.windowsLauncher
import cz.loplex.dogvision.packaging.windowsRuntime
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The desktop window in Compose Multiplatform, for Linux and Windows:
// gui-core's picture of a photo, a video or the camera, with ui's controls beside it.
// It takes gui-core's options of a window, which share jvm-common's view options with the command line.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
    id("cz.loplex.dogvision.detekt")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("cz.loplex.dogvision.packaging")
    id("cz.loplex.dogvision.ktlint")
}

/** The class whose main the window and its JARs start with. */
val mainClassName = "cz.loplex.dogvision.desktop.MainKt"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":gui-core"))
            implementation(project(":ui"))
            implementation(libs.compose.multiplatform.desktop)
            implementation(libs.compose.multiplatform.material3)
            // This machine's natives, which the run and linuxUberJar take.
            runtimeOnly(compose.desktop.currentOs)
            for (natives in glNatives()) runtimeOnly(natives)
        }
    }
}

// What runs the window on Windows on x86-64 in place of this machine's natives:
// Compose's for it, before LWJGL's and ANGLE.
// Only windowsUberJar and windowsLauncher take it.
windowsRuntime(listOf(libs.compose.multiplatform.desktop.windows.x64))

/** The icons, the second launcher's properties and what else the packages take. */
val packaging = layout.projectDirectory.dir("packaging")

/** The JDK Compose runs the window on, Temurin, as the packages' runtimes are, which build-logic says why. */
val packagingJdk = packagingJdk()

// The window as Compose runs it; its packages, the tar.gz and Windows's MSI among them, are :packaging's.
compose.desktop {
    application {
        mainClass = mainClassName
        javaHome = packagingJdk.get().metadata.installationPath.asFile.path
        nativeDistributions {
            packageName = "dog-vision-compose"
            packageVersion = providers.gradleProperty("appVersion").get()
            description = "How a dog or another animal sees a photo, a video or the camera"
            vendor = "Martin Lopatář"
            // Beyond the modules Compose always takes: what suggestRuntimeModules finds the classes using.
            modules("java.instrument", "jdk.unsupported")
            linux {
                iconFile = packaging.file("dog-vision.png")
            }
        }
    }
}

/** The JARs the JVM target runs on, here and on Windows. */
val jvmRuntimeClasspath = configurations.named("jvmRuntimeClasspath")
val windowsRuntime = configurations.named("windowsRuntime")

/** The window's own JAR, which each uber JAR below merges with the JARs the window needs. */
val windowJar = tasks.named<Jar>("jvmJar").flatMap { it.archiveFile }

/** ANGLE for Windows on ARM, which LWJGL's and skiko's natives here are not for. */
val armAngle = "nucleus/native/win32-aarch64"

// The JAR with this machine's natives, in place of Compose's packageUberJarForCurrentOS,
// which merges the JARs without the checks of uberJar.
val linuxUberJarNotices =
    uberJarLicences(
        "linuxUberJarLicences",
        "dog-vision-compose-linux-x64-${compose.desktop.application.nativeDistributions.packageVersion}.jar",
        jvmRuntimeClasspath,
    )
val linuxUberJar = tasks.register<Jar>("linuxUberJar") {
    description = "Assembles build/compose/jars/dog-vision-compose-linux-x64-<version>.jar, the window for this " +
        "machine."
    group = "compose desktop"
    destinationDirectory = layout.buildDirectory.dir("compose/jars")
    uberJar(
        "dog-vision-compose-linux-x64-${compose.desktop.application.nativeDistributions.packageVersion}.jar",
        mainClassName,
        windowJar,
        jvmRuntimeClasspath,
        linuxUberJarNotices,
    )
}
artifact(linuxUberJar)

// Built on any machine, as jpackage's installers are not: Windows's natives and ANGLE in place of this machine's.
val windowsUberJarNotices =
    uberJarLicences(
        "windowsUberJarLicences",
        "dog-vision-compose-windows-x64-${compose.desktop.application.nativeDistributions.packageVersion}.jar",
        windowsRuntime,
    )
val windowsUberJar = tasks.register<Jar>("windowsUberJar") {
    description = "Assembles build/compose/jars/dog-vision-compose-windows-x64-<version>.jar, the window for " +
        "Windows."
    group = "compose desktop"
    destinationDirectory = layout.buildDirectory.dir("compose/jars")
    uberJar(
        "dog-vision-compose-windows-x64-${compose.desktop.application.nativeDistributions.packageVersion}.jar",
        mainClassName,
        windowJar,
        windowsRuntime,
        windowsUberJarNotices,
        excludes = listOf("$armAngle/**"),
    )
}
artifact(windowsUberJar)

// dog-vision-compose.exe, the MSI's main launcher, which :packaging takes, on the JARs windowsUberJar merges,
// and on a runtime of the modules the app image's has: Compose's own and those added above.
// Compose's launchers pass the Java option, with which its application gives Swing the system's look.
windowsLauncher(
    name = "dog-vision-compose",
    ownJar = windowJar,
    classpath = windowsRuntime,
    mainClass = mainClassName,
    javaOptions = listOf("-Dcompose.application.configure.swing.globals=true"),
    runtimeModules = compose.desktop.application.nativeDistributions.modules,
    leftOut = listOf(armAngle),
)
