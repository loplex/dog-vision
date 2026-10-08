import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The colour model and everything else that needs no platform,
// built for the JVM, which the Android app runs it on, and for JavaScript.
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
    // A library the web page bundles; its tests run in Node.js, which needs no browser installed.
    js {
        nodejs()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(project(":testing"))
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

/** The documents, which ModelDocTest holds to what core does. */
val docs = rootProject.layout.projectDirectory.dir("docs")

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    inputs.file(docs.file("model.md")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("dogvision.docs", docs.asFile.absolutePath)
}
