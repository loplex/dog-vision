package cz.loplex.dogvision.packaging.jvm

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.CopySpec
import org.gradle.api.provider.Provider

/**
 * The names a package installs JARs of [artifacts] under,
 * side by side in one folder, where a JAR's own name will not do, keyed by the JAR's path:
 * where two JARs have the same name, each of them with its module's group before it.
 * `org.jetbrains.compose.runtime`'s runtime-desktop is one,
 * a shim of `androidx.compose.runtime`'s of the same name and version.\
 * A JAR the map has no key for keeps its own name.
 */
fun installedJarNames(artifacts: Set<ResolvedArtifactResult>): Map<String, String> =
    artifacts.groupBy { it.file.name }.values.filter { it.size > 1 }.flatten().associate { artifact ->
        val id = artifact.id.componentIdentifier
        check(id is ModuleComponentIdentifier) { "Two JARs named ${artifact.file.name}, one of them $id's" }
        artifact.file.path to "${id.group}-${artifact.file.name}"
    }

/** Copies each JAR [names] has a key for under the name it gives, as [installedJarNames] makes them. */
fun CopySpec.renameJars(names: Provider<Map<String, String>>) {
    eachFile { names.get()[file.path]?.let { name = it } }
}
