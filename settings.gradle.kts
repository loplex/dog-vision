pluginManagement {
    // The packaging tasks the desktop modules share.
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Downloads a JDK a toolchain asks for that this machine lacks: the desktop packages' Temurin.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // Node.js for the Kotlin/JS modules' tests and bundles, laid out as the Kotlin Gradle plugin downloads it.
        ivy("https://nodejs.org/dist") {
            name = "Node.js distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
    }
}

rootProject.name = "dog-vision"

include(":app", ":cli", ":core", ":desktop", ":desktop-core", ":gl", ":swing", ":testing", ":texts", ":ui", ":web")
