@file:Suppress("UnstableApiUsage")

import cz.loplex.dogvision.packaging.artifact
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("cz.loplex.dogvision.ktlint")
}

/**
 * Writes the app's name, which the manifest can take only from a string resource,
 * out of each language's strings of the texts module into a resource folder of its own,
 * so that the texts stay the one place it is worded.
 */
abstract class WriteAppName : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun write() {
        val directory = output.get().asFile
        directory.deleteRecursively()
        for (file in sources.files) {
            val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val names = strings.getElementsByTagName("string")
            val name = (0 until names.length).map { names.item(it) as Element }
                .single { it.getAttribute("name") == "app_name" }
            val resources = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
            resources.appendChild(resources.createElement("resources")).appendChild(resources.importNode(name, true))
            val folder = directory.resolve(file.parentFile.name)
            folder.mkdirs()
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.ENCODING, "utf-8")
            }.transform(DOMSource(resources), StreamResult(folder.resolve("app_name.xml")))
        }
    }
}

val writeAppName = tasks.register<WriteAppName>("writeAppName") {
    description = "Writes the texts' app_name into a string resource of each language."
    sources.from(fileTree(rootProject.file("texts/strings")) { include("values/strings.xml", "values-*/strings.xml") })
    output = layout.buildDirectory.dir("generated/appName")
}

/** Copies one file into a folder of its own, to be a generated source folder of a variant. */
abstract class CopyFile : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val directory = output.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        val file = source.get().asFile
        file.copyTo(directory.resolve(file.name))
    }
}

val copyDocsPhoto = tasks.register<CopyFile>("copyDocsPhoto") {
    description = "Copies the documents' apple photo into the instrumented tests' assets, for DocsScreenshotTest."
    source = rootProject.file("docs/images/shiny-red-apples.jpg")
    output = layout.buildDirectory.dir("generated/docsPhoto")
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(writeAppName, WriteAppName::output)
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(copyDocsPhoto, CopyFile::output)
    }
}

android {
    namespace = "cz.loplex.dogvision"
    compileSdk = 37

    defaultConfig {
        applicationId = "cz.loplex.dogvision"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = providers.gradleProperty("appVersion").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // The languages the app speaks, from its values-* folders, for the system's per-app language setting.
        generateLocaleConfig = true
    }
}

// The app's artifact, the debug APK, by the task of Android's plugin that writes it: assembleDebug declares no output.
artifact("packageDebug")

dependencies {
    implementation(project(":core"))
    implementation(project(":gl"))
    implementation(project(":texts"))
    implementation(project(":ui"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(project(":testing"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
