package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.cli.WindowsGl
import cz.loplex.dogvision.gl.Gl
import org.lwjgl.system.Configuration
import java.nio.ByteBuffer

/**
 * gl's [Gl] with what the window's [Passes] need besides: a frame uploaded from a direct buffer, a buffer mapped to be
 * read, and the framebuffer bound cleared.
 */
interface DesktopGl : Gl {
    /** Allocates the texture bound, filled from the direct buffer [pixels], from its position to its limit. */
    fun texImage2D(
        target: Int,
        level: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: ByteBuffer,
    )

    /**
     * What [read] makes of the first [size] bytes of the buffer bound to [target], mapped for reading while it runs;
     * mapping it waits for the GPU to finish writing it.
     */
    fun <T> readMapped(target: Int, size: Int, read: (ByteBuffer) -> T): T

    /** Clears the framebuffer bound to transparent black. */
    fun clear()
}

/**
 * An OpenGL context with no window of its own to draw into, current on the thread that makes it, and [gl] to draw in
 * it: GL draws into framebuffers of its own, which are read back to be shown.
 */
interface GlContext : AutoCloseable {
    val gl: DesktopGl

    /** What GL says it is running on, as `GL_RENDERER` and `GL_VERSION` name it. */
    val renderer: String
    val version: String

    /** Whether GL renders in software, on the CPU. */
    val software: Boolean

    companion object {
        /**
         * The context this system draws in, current on the calling thread, with what [prepare] makes in it: on
         * Windows, ANGLE's, and WGL's where ANGLE's cannot be made or [prepare] fails in it, unless [windowsGl] names
         * one of them; elsewhere, as on Linux, one on a GPU that EGL's device platform names. Throws
         * IllegalStateException, or UnsatisfiedLinkError where a library is missing, if none can be made and prepared,
         * having closed any it made.
         */
        fun <T> open(windowsGl: WindowsGl? = null, prepare: (DesktopGl) -> T): Pair<GlContext, T> {
            if (!onWindows) {
                check(windowsGl == null) { "--gl chooses how to draw on Windows, and this is not Windows" }
                return prepared({ EglContext.onDevice() }, prepare)
            }
            return when (windowsGl) {
                WindowsGl.ANGLE -> prepared(EglContext::angle, prepare)

                WindowsGl.WGL -> prepared(::WglContext, prepare)

                null -> fallingBack(EglContext::angle, ::WglContext, prepare) { angle ->
                    System.err.println("ANGLE cannot draw here, so WGL draws: ${angle.message}")
                }
            }
        }

        /** The context [open] makes, with what [prepare] makes in it; the context is closed if [prepare] fails. */
        private fun <T> prepared(open: () -> GlContext, prepare: (DesktopGl) -> T): Pair<GlContext, T> {
            val context = open()
            return try {
                context to prepare(context.gl)
            } catch (error: Throwable) {
                context.close()
                throw error
            }
        }

        /**
         * The context [first] makes, with what [prepare] makes in it, or, where either fails, [second]'s, once
         * [onFallback] is told why the first failed.
         */
        internal fun <T> fallingBack(
            first: () -> GlContext,
            second: () -> GlContext,
            prepare: (DesktopGl) -> T,
            onFallback: (Throwable) -> Unit,
        ): Pair<GlContext, T> {
            val failure = try {
                return prepared(first, prepare)
            } catch (error: IllegalStateException) {
                error
            } catch (error: UnsatisfiedLinkError) {
                error
            }
            onFallback(failure)
            return try {
                prepared(second, prepare)
            } catch (error: IllegalStateException) {
                throw IllegalStateException("${failure.message}; ${error.message}", error)
            }
        }
    }
}

/**
 * Loads LWJGL's library of OpenGL ES or of desktop OpenGL, as [create], GLES's or GL's, does, for a context of it.
 *
 * LWJGL takes the functions of only one of the two at a time in a JVM ("setFunctionMissingAddresses has been called
 * already"), and a class of theirs loads its library when it is first used unless told not to; a class that could not
 * load it would stay unusable, failing with ExceptionInInitializerError. So LWJGL is told to load neither by itself,
 * and each context loads the library of its API and unloads it when it closes: the window can fall back from ANGLE's
 * OpenGL ES to WGL's desktop OpenGL, and the tests open either in one JVM.
 */
internal fun loadApi(create: () -> Unit) {
    Configuration.OPENGL_EXPLICIT_INIT.set(true)
    Configuration.OPENGLES_EXPLICIT_INIT.set(true)
    create()
}
