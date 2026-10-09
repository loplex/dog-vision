package cz.loplex.dogvision.packaging.linux

import cz.loplex.dogvision.packaging.jvm.installedJarNames
import cz.loplex.dogvision.packaging.jvm.renameJars
import cz.loplex.dogvision.packaging.licenses.javaRuntimeNote
import cz.loplex.dogvision.packaging.licenses.thirdPartyLicenses
import cz.loplex.dogvision.packaging.packagingJdk
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import java.util.concurrent.Callable

/**
 * Registers package<Package>TarGz, which packs into build/distributions <[packageName]>-<version>-linux-x64.tar.gz:
 * jpackage's app image of the window [packageName],
 * with a runtime of its own, and dog-vision-cli's launcher beside the window's,
 * to unpack and run anywhere on Linux on x86-64 without installing it.
 * The window's module cannot hold the command line's launcher, as one program does not use another:
 * this module, which takes both, makes the image.
 *
 * The JARs are those [jars] and [cliJars] resolve, a JAR of both once,
 * under the names [installedJarNames] gives them; jpackage puts every one on each launcher's classpath.\
 * The natives of the JARs whose names start with one of [nativesBeside] are unpacked beside them too,
 * as Compose's app image has skiko's, which a Java option then points to as $APPDIR,
 * so that it does not unpack them into the user's home.\
 * The window's launcher starts [mainClass] from [module]'s JAR with [javaOptions];
 * the runtime has the modules [moduleLists] name, as the launchers' .modules for Windows do.\
 * The archive holds the project's LICENSE and the notices of what it holds that is not this project's own,
 * the runtime's among them, in the app's folder.
 */
fun Project.windowTarGz(
    packageName: String,
    module: String,
    jars: NamedDomainObjectProvider<out Configuration>,
    cliJars: NamedDomainObjectProvider<out Configuration>,
    mainClass: String,
    javaOptions: List<String> = emptyList(),
    nativesBeside: List<String> = emptyList(),
    moduleLists: FileCollection,
): TaskProvider<Tar> {
    // The package's name in the tasks' names, DogVisionSwing and dogVisionSwing of dog-vision-swing.
    val suffix = packageName.split('-').joinToString("") { word -> word.replaceFirstChar(Char::uppercase) }
    val prefix = suffix.replaceFirstChar(Char::lowercase)
    val work = "linux/$packageName"
    val version = providers.gradleProperty("appVersion")
    val jdk = packagingJdk()
    val packaging = rootProject.layout.projectDirectory.dir("gui-compose/packaging")

    val artifacts = jars.flatMap { it.incoming.artifacts.resolvedArtifacts }
    val names = artifacts.map(::installedJarNames)
        .zip(cliJars.flatMap { it.incoming.artifacts.resolvedArtifacts }.map(::installedJarNames)) { own, cli ->
            cli + own
        }
    val mainJar = artifacts.zip(names) { resolved, renamed ->
        val own = resolved.single { (it.id.componentIdentifier as? ProjectComponentIdentifier)?.projectPath == module }
        renamed[own.file.path] ?: own.file.name
    }
    val appJars = tasks.register("${prefix}AppImageJars", Sync::class.java) {
        description = "Gathers in build/$work/app-image-jars the JARs of $packageName's app image."
        group = "distribution"
        into(layout.buildDirectory.dir("$work/app-image-jars"))
        from(files(jars, cliJars)) { renameJars(names) }
        val unpacked = files(jars).filter { jar -> nativesBeside.any { jar.name.startsWith(it) } }
        from(Callable { unpacked.files.map(::zipTree) }) { include("*.so", "*.so.sha256") }
        // Two JARs of one name would leave one launcher on the other's.
        duplicatesStrategy = DuplicatesStrategy.FAIL
    }
    val appImage = tasks.register("${prefix}AppImage", AppImage::class.java) {
        description = "Makes build/$work/app-image/$packageName, jpackage's app image with a runtime of its own."
        group = "distribution"
        jdkHome.set(jdk.map { it.metadata.installationPath.asFile.path })
        this.jars.from(appJars)
        this.mainJar.set(mainJar)
        this.mainClass.set(mainClass)
        imageName.set(packageName)
        appVersion.set(version)
        modules.set(emptyList())
        this.moduleLists.from(moduleLists)
        this.javaOptions.set(javaOptions)
        icon.set(packaging.file("dog-vision.png"))
        launchers.set(mapOf("dog-vision-cli" to packaging.file("dog-vision-cli.properties").asFile))
        destination.set(layout.buildDirectory.dir("$work/app-image"))
        input.set(layout.buildDirectory.dir("$work/app-image-input"))
    }
    val notices = thirdPartyLicenses("${prefix}TarGzLicences", listOf(jars, cliJars)) {
        artifactName.set("The archive of $packageName for Linux")
        notes.add(jdk.map { javaRuntimeNote("lib/runtime", it.metadata.javaRuntimeVersion) })
    }.flatMap { it.notices }
    return tasks.register("package${suffix}TarGz", Tar::class.java) {
        description = "Packs $packageName's app image into build/distributions/$packageName-<version>-linux-x64.tar.gz."
        group = "distribution"
        archiveFileName.set(version.map { "$packageName-$it-linux-x64.tar.gz" })
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        compression = Compression.GZIP
        // The launchers and the runtime's jspawnhelper, which starts ffmpeg, stay executable:
        // Gradle's archives otherwise give every file the same mode.
        eachFile { permissions { unix(if (file.canExecute()) "755" else "644") } }
        from(appImage.flatMap { it.destination })
        // The licence, and what the archive holds that is not this project's own, in the app's folder.
        from(files(rootProject.file("LICENSE"), notices)) { into(packageName) }
    }
}
