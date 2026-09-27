import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// What a desktop window draws with, in no toolkit: a photo, a video or the camera through ffmpeg, rendered by gl's
// passes in an offscreen GL context, and read back into whatever image the window shows. Neither Compose nor skiko
// may come in here, so that a window in another toolkit can do without them.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/** Whether this machine is Windows, where the tests draw through ANGLE and WGL, as the windows do. */
val onWindows = System.getProperty("os.name").startsWith("Windows")

/** LWJGL's native part for this machine: Linux's or Windows's, on x86-64. */
val lwjglNatives = if (onWindows) "natives-windows" else "natives-linux"

/**
 * The LWJGL modules with a native part on this machine: desktop GL's is for the tests of LwjglGl through EGL on Linux,
 * and on Windows GLFW makes the WGL context.
 */
val lwjglWithNatives = listOf(libs.lwjgl.asProvider(), libs.lwjgl.opengles, libs.lwjgl.opengl) +
    if (onWindows) listOf(libs.lwjgl.glfw) else emptyList()

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        jvmMain.dependencies {
            api(project(":cli"))
            implementation(project(":gl"))
            implementation(libs.lwjgl.asProvider())
            implementation(libs.lwjgl.egl)
            implementation(libs.lwjgl.glfw)
            implementation(libs.lwjgl.opengl)
            implementation(libs.lwjgl.opengles)
            api(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(project(":testing"))
            implementation(kotlin("test"))
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
            // The natives only the tests take: each window takes those of the system it is packaged for.
            for (library in lwjglWithNatives) {
                runtimeOnly("${library.get().module}:${libs.versions.lwjgl.get()}:$lwjglNatives")
            }
            if (onWindows) runtimeOnly(libs.nucleus.angle.natives)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
