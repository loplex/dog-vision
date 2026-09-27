import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// What the tests of core, the Android app, the web page and the desktop window hold a renderer to: core's reference
// pattern, the images core renders of it, and how far apart two images are. A library for tests only, built for the
// JVM, which the Android app's instrumented tests run it on too, and for JavaScript.
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
            api(project(":core"))
        }
    }
}
