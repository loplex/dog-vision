// The tasks the packages are made with, applied as the plugin below, and the natives the desktop modules run with.
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

// The functions that write the packages' licences are tested here; `check` runs the tests.
dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // The licences' texts, which the tests read as the build does.
    systemProperty("licenceTexts", rootDir.resolve("../packaging/licenses").path)
}

// Held to .editorconfig as the main build's modules are; their `check` runs this build's.
ktlint {
    version = libs.versions.ktlint.cli
}
