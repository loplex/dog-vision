import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The controls of the view in Compose Multiplatform, shared by the Android app and a desktop window: its sections,
// sliders and choices, the facts about the species, and what each control means.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "cz.loplex.dogvision.ui"
        compileSdk = 36
        minSdk = 26
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":texts"))
            implementation(libs.compose.multiplatform.runtime)
            implementation(libs.compose.multiplatform.foundation)
            implementation(libs.compose.multiplatform.ui)
            implementation(libs.compose.multiplatform.material3)
        }
        // The controls are tested on the JVM, in Compose's test scene, with Skia's native library for this machine.
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.compose.multiplatform.ui.test)
            implementation(compose.desktop.currentOs)
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
