package cz.loplex.dogvision.desktop

import org.lwjgl.glfw.GLFW.GLFW_CLIENT_API
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_API
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDefaultWindowHints
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwGetError
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.GL_RENDERER
import org.lwjgl.opengl.GL33C.GL_VERSION
import org.lwjgl.opengl.GL33C.glGetString
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.MemoryUtil.memUTF8Safe

/**
 * A desktop OpenGL 3.3 core context of the graphics driver's own, through WGL on Windows, where ANGLE cannot start:
 * GLFW makes it on a window that is never shown, since WGL makes a context only for a window's device context, and GL
 * draws into framebuffers of its own, as in the other contexts.
 *
 * LWJGL's library of desktop OpenGL is loaded for the context and unloaded with it, as [loadApi] says why.
 *
 * Throws IllegalStateException where GLFW or a 3.3 core context is missing, having undone what it did.
 */
class WglContext : GlContext {
    private val window: Long

    override val renderer: String
    override val version: String

    init {
        check(glfwInit()) { "GLFW cannot start: ${glfwError()}" }
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        window = glfwCreateWindow(1, 1, "dog-vision-gl", NULL, NULL)
        if (window == NULL) {
            val error = glfwError()
            glfwTerminate()
            throw IllegalStateException("Cannot make an OpenGL 3.3 core context: $error")
        }
        glfwMakeContextCurrent(window)
        try {
            loadApi(GL::create)
            GL.createCapabilities()
            renderer = glGetString(GL_RENDERER).orEmpty()
            version = glGetString(GL_VERSION).orEmpty()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override val gl = LwjglGl()

    /**
     * Whether GL renders in software, on the CPU, as Mesa's llvmpipe does, where Wine runs on it, and Windows's own GL
     * 1.1, which no 3.3 context gets, does.
     */
    override val software: Boolean get() = listOf("llvmpipe", "GDI Generic").any { it in renderer }

    override fun close() {
        glfwMakeContextCurrent(NULL)
        GL.setCapabilities(null)
        glfwDestroyWindow(window)
        glfwTerminate()
        GL.destroy()
    }

    /** What GLFW said of the last error, or that it said nothing. */
    private fun glfwError(): String = MemoryStack.stackPush().use { stack ->
        val description = stack.mallocPointer(1)
        val code = glfwGetError(description)
        memUTF8Safe(description[0]) ?: "GLFW error 0x${code.toString(16)}"
    }
}
