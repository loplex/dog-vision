// The web page: a photo shown as a species sees it, rendered by the shared shaders in WebGL 2.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "dog-vision.js"
            }
            // In headless Chrome, told in karma.config.d to render WebGL 2 in software, or with -PwebTestsOnGpu on the
            // machine's GPU through Vulkan.
            testTask {
                useKarma {
                    useChromeHeadless()
                }
                val onGpu = providers.gradleProperty("webTestsOnGpu").map { it != "false" }.orElse(false)
                inputs.property("webTestsOnGpu", onGpu)
                environment("DOG_VISION_TESTS_ON_GPU", onGpu.get().toString())
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":core"))
            implementation(project(":gl"))
            implementation(project(":texts"))
        }
        jsTest.dependencies {
            implementation(project(":testing"))
            implementation(kotlin("test"))
        }
    }
}
