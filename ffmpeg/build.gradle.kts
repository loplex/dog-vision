import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ffmpeg and ffprobe, found and run: on the PATH, where they were installed after a window started,
// or on Windows where a window downloaded them.
// Needs no toolkit and no GPU.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":texts"))
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

// The release branch of ffmpeg's builds for Windows that FfmpegDownload takes, from libs.versions.toml.
val ffmpegProperties = tasks.register<WriteProperties>("ffmpegProperties") {
    description = "Writes build/generated/ffmpeg/ffmpeg.properties, the branch of ffmpeg the windows download."
    destinationFile = layout.buildDirectory.file("generated/ffmpeg/ffmpeg.properties")
    property("windowsBranch", libs.versions.ffmpeg.windows)
}

tasks.named<ProcessResources>("jvmProcessResources") {
    from(ffmpegProperties) {
        into("cz/loplex/dogvision/ffmpeg")
    }
}
