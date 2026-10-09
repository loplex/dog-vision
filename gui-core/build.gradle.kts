import cz.loplex.dogvision.packaging.jvm.glNatives
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// What a desktop window draws with, in neither Compose nor skiko: a photo, a video or the camera through ffmpeg,
// rendered by gl's passes in an offscreen GL context, and read back into whatever image the window shows.
// Neither may come in here, so that a window in another toolkit can do without them;
// Java's own AWT and Swing are all it uses.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
    id("cz.loplex.dogvision.detekt")
    id("cz.loplex.dogvision.ktlint")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        jvmMain.dependencies {
            api(project(":jvm-common"))
            api(project(":ffmpeg"))
            implementation(project(":gl"))
            implementation(libs.lwjgl.asProvider())
            implementation(libs.lwjgl.egl)
            implementation(libs.lwjgl.glfw)
            implementation(libs.lwjgl.opengl)
            implementation(libs.lwjgl.opengles)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(project(":testing"))
            implementation(kotlin("test"))
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
            // The natives only the tests take: each window takes those of the system it is packaged for.
            for (natives in glNatives()) runtimeOnly(natives)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

// The windows' icon, the packages' own, as a resource beside windowIcon, so that the file is kept in one place.
tasks.named<ProcessResources>("jvmProcessResources") {
    from(rootProject.layout.projectDirectory.file("gui-compose/packaging/dog-vision.png")) {
        into("cz/loplex/dogvision/desktop")
    }
}
