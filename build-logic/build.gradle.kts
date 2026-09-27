// The tasks the desktop modules make their Linux packages with, applied as the plugin below.
plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
}

gradlePlugin {
    plugins {
        create("packaging") {
            id = "cz.loplex.dogvision.packaging"
            implementationClass = "cz.loplex.dogvision.packaging.PackagingPlugin"
        }
    }
}

// Held to .editorconfig as the main build's modules are; their `check` runs this build's.
ktlint {
    version = libs.versions.ktlint.cli
}
