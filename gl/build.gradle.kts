import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The GLSL ES 3.00 programs that render a view, and the passes that run them through a small GL interface, shared by
// the Android app's OpenGL ES and the web page's WebGL 2.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
