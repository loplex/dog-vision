import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The GLSL ES 3.00 programs that render a view, and the passes that run them through a small GL interface,
// shared by the Android app's OpenGL ES, the desktop windows' OpenGL ES or OpenGL 3.3, and the web page's WebGL 2.
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
    js {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
        }
    }
}
