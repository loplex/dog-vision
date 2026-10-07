@file:Suppress("UnstableApiUsage")

pluginManagement {
    // The tasks the packages are made with, and the natives the desktop modules share.
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
        // Temurin's jmods for Windows, which the Windows runtimes are linked from, from Adoptium's releases on GitHub:
        // net.adoptium:temurin25-binaries:25.0.4.1+1 is the release jdk-25.0.4.1+1 of adoptium/temurin25-binaries.
        ivy("https://github.com/adoptium") {
            name = "Temurin releases"
            patternLayout { artifact("[module]/releases/download/jdk-[revision]/[artifact].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("net.adoptium") }
        }
    }
}

rootProject.name = "dog-vision"

include(
    ":android",
    ":cli",
    ":core",
    ":ffmpeg",
    ":gl",
    ":gui-compose",
    ":gui-core",
    ":gui-swing",
    ":testing",
    ":texts",
    ":ui",
    ":web",
)
// The packages, made apart from the modules they hold.
include(":packaging")
