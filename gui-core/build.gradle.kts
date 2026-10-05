import cz.loplex.dogvision.packaging.glNatives
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// What a desktop window draws with, in neither Compose nor skiko: a photo, a video or the camera through ffmpeg,
// rendered by gl's passes in an offscreen GL context, and read back into whatever image the window shows.
// Neither may come in here, so that a window in another toolkit can do without them;
// Java's own AWT and Swing are all it uses.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        jvmMain.dependencies {
            api(project(":cli"))
            implementation(project(":gl"))
            implementation(libs.lwjgl.asProvider())
            implementation(libs.lwjgl.egl)
            implementation(libs.lwjgl.glfw)
            implementation(libs.lwjgl.opengl)
            implementation(libs.lwjgl.opengles)
            api(libs.kotlinx.coroutines.core)
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

// The release branch of ffmpeg's builds for Windows that FfmpegDownload takes, from libs.versions.toml.
val ffmpegProperties = tasks.register<WriteProperties>("ffmpegProperties") {
    description = "Writes build/generated/ffmpeg/ffmpeg.properties, the branch of ffmpeg the windows download."
    destinationFile = layout.buildDirectory.file("generated/ffmpeg/ffmpeg.properties")
    property("windowsBranch", libs.versions.ffmpeg.windows)
}

// The windows' icon, the packages' own, as a resource beside windowIcon, so that the file is kept in one place,
// and ffmpeg's branch beside it.
tasks.named<ProcessResources>("jvmProcessResources") {
    from(rootProject.layout.projectDirectory.file("gui-compose/packaging/dog-vision.png")) {
        into("cz/loplex/dogvision/desktop")
    }
    from(ffmpegProperties) {
        into("cz/loplex/dogvision/desktop")
    }
}
