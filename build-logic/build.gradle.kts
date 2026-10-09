// The tasks the packages are made with, applied as the packaging plugin below,
// the natives the desktop modules run with, ktlint and detekt as the modules apply them,
// and where the Kotlin/JS modules get Node.js.
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
        create("ktlint") {
            id = "cz.loplex.dogvision.ktlint"
            implementationClass = "cz.loplex.dogvision.KtlintPlugin"
        }
        create("detekt") {
            id = "cz.loplex.dogvision.detekt"
            implementationClass = "cz.loplex.dogvision.DetektPlugin"
        }
        create("nodejs") {
            id = "cz.loplex.dogvision.nodejs"
            implementationClass = "cz.loplex.dogvision.NodeJsPlugin"
        }
    }
}

// The functions that write the packages' licences are tested here; `check` runs the tests.
dependencies {
    implementation(libs.ktlint.gradle)
    implementation(libs.detekt.gradle)
    // The modules' own Kotlin Gradle plugin, which the main build's root puts on their class path,
    // is the one whose types the Node.js plugin looks for; a second copy here would match none of them.
    compileOnly(libs.kotlin.gradle)
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
