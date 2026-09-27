import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The command line on the JVM, as the Python program has it: its options, a photo read as it is meant to be seen, and
// the photo converted at full size by core. It needs no toolkit and no GPU, so that it runs alone as well as through
// the desktop window's own main, which reads the same options.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass = "cz.loplex.dogvision.cli.MainKt"
                applicationName = "dog-vision-cli"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":texts"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

// One JAR with core and the Kotlin standard library in it, which `java -jar` runs alone on a JDK 17 or newer.
tasks.register<Jar>("uberJar") {
    description = "Assembles build/jars/dog-vision-cli.jar, the command line with everything it needs."
    group = "distribution"
    archiveFileName = "dog-vision-cli.jar"
    destinationDirectory = layout.buildDirectory.dir("jars")
    manifest { attributes("Main-Class" to "cz.loplex.dogvision.cli.MainKt") }
    from(tasks.named<Jar>("jvmJar").map { zipTree(it.archiveFile) })
    from(configurations.named("jvmRuntimeClasspath").map { classpath -> classpath.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
}
