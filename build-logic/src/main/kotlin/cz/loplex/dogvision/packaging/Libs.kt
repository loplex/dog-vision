package cz.loplex.dogvision.packaging

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension

/** The build's version catalog, libs.versions.toml. */
internal fun Project.libs(): VersionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
