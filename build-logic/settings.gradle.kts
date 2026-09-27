// The build's own Gradle code, which the modules that make packages share: an included build, which the main build's
// settings take in through pluginManagement, so that its plugins are applied by id as any other.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // The main build's catalog, so that both take one version of each plugin.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
