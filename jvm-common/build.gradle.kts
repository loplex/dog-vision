import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// What the command line and the windows share, in no toolkit:
// the options of the view, which both read from their command lines, a photo read as it is meant to be seen,
// and a photo or a video converted at full size, the video through ffmpeg.
// The package dog-vision-common carries it on Linux, beside the JARs it passes on.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
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
            api(project(":core"))
            api(project(":texts"))
            api(project(":ffmpeg"))
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
