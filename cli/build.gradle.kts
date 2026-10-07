import cz.loplex.dogvision.packaging.artifact
import cz.loplex.dogvision.packaging.uberJar
import cz.loplex.dogvision.packaging.uberJarLicences
import cz.loplex.dogvision.packaging.windowsLauncher
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The command line on the JVM: its options, a photo read as it is meant to be seen,
// and the photo converted at full size by core, or a video, frame by frame, through ffmpeg.
// It needs no toolkit and no GPU.
// Its view options are the windows' as well, which read them through gui-core.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    id("cz.loplex.dogvision.packaging")
}

/** The class whose main the command line and its JARs start with. */
val mainClassName = "cz.loplex.dogvision.cli.MainKt"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass = mainClassName
                applicationName = "dog-vision-cli"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":texts"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(project(":ffmpeg"))
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

/** The documents' figures, which core renders from the photo beside them. */
val figures = rootProject.layout.projectDirectory.dir("docs/images")

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    inputs.dir(figures).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("dogvision.figures", figures.asFile.absolutePath)
}

tasks.register<JavaExec>("renderFigures") {
    description = "Renders the documents' figures in docs/images from the apple photo there."
    group = "documentation"
    val test = kotlin.jvm().compilations.getByName("test")
    classpath = files(test.output.allOutputs, test.runtimeDependencyFiles)
    mainClass = "cz.loplex.dogvision.cli.FiguresKt"
    args(figures.asFile.absolutePath)
}

// One JAR with core and the Kotlin standard library in it, which `java -jar` runs alone on a JDK 17 or newer.
val uberJarNotices =
    uberJarLicences("uberJarLicences", "dog-vision-cli.jar", configurations.named("jvmRuntimeClasspath"))
val uberJar = tasks.register<Jar>("uberJar") {
    description = "Assembles build/jars/dog-vision-cli.jar, the command line with everything it needs."
    group = "distribution"
    destinationDirectory = layout.buildDirectory.dir("jars")
    uberJar(
        "dog-vision-cli.jar",
        mainClassName,
        tasks.named<Jar>("jvmJar").flatMap { it.archiveFile },
        configurations.named("jvmRuntimeClasspath"),
        uberJarNotices,
    )
}
artifact(uberJar)

/**
 * The modules of the runtime on Windows: what jdeps --print-module-deps finds the JAR using;
 * ImageIO, which reads and writes the photos, is in java.desktop.
 */
val windowsModules = listOf("java.base", "java.desktop")

// dog-vision-cli.exe, in a console, on the JARs uberJar merges:
// in the MSI beside the windows, and alone in the zip, both of which :packaging makes.
windowsLauncher(
    name = "dog-vision-cli",
    ownJar = tasks.named<Jar>("jvmJar").flatMap { it.archiveFile },
    classpath = configurations.named("jvmRuntimeClasspath"),
    mainClass = mainClassName,
    console = true,
    runtimeModules = windowsModules,
)
