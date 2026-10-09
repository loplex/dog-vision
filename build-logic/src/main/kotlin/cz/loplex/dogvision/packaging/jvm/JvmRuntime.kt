@file:Suppress("UnstableApiUsage")

package cz.loplex.dogvision.packaging.jvm

import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment

/**
 * Creates <module>RuntimeClasspath, which resolves [module]'s JVM target as a program runs it,
 * with the attributes the module's own jvmRuntimeClasspath has: the module's JAR, and every JAR it needs,
 * with the ids of their components, which [installedJarNames] names them by.
 * This is how a package takes a module that the package does not live in.
 */
fun Project.jvmRuntimeOf(module: String): NamedDomainObjectProvider<out Configuration> {
    val name = module.removePrefix(":")
    val scope = configurations.dependencyScope("${name}Runtime")
    dependencies.add(scope.name, dependencies.project(mapOf("path" to module)))
    return configurations.resolvable("${name}RuntimeClasspath") {
        extendsFrom(scope.get())
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
            attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM),
            )
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            attribute(KOTLIN_PLATFORM, "jvm")
        }
    }
}

/** Kotlin's attribute of the platform a variant is for, by name: build-logic has no Kotlin plugin to take it from. */
private val KOTLIN_PLATFORM = Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java)
