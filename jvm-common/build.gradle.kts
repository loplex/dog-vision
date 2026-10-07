import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// What the command line and the windows share, in no toolkit:
// the options of the view, which both read from their command lines, and a photo read as it is meant to be seen.
// The package dog-vision-common carries it on Linux, beside the JARs of core and texts it passes on.
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
            api(project(":core"))
            api(project(":texts"))
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
