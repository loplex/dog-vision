package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.testing.pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Contexts of OpenGL ES and of desktop OpenGL open one after the other in one JVM, though LWJGL takes the functions of
 * only one of the two APIs at a time, and a context falls back to another where it cannot be prepared, as the window
 * falls back from ANGLE's OpenGL ES to WGL's desktop OpenGL; [TestApi] says which contexts they are on this system.
 */
class GlContextTest {
    @Test
    fun eitherApiOpensAfterTheOther() {
        for (api in listOf(TestApi.ES, TestApi.DESKTOP, TestApi.ES)) {
            api.open().use { context ->
                Passes(context.gl).release()
                assertTrue(context.version.isNotEmpty(), "${api.title} names no version")
            }
        }
    }

    @Test
    fun theSecondContextDrawsWhereTheFirstCannotBePrepared() {
        val reasons = mutableListOf<String?>()
        val (context, passes) = GlContext.fallingBack(
            TestApi.ES.open,
            TestApi.DESKTOP.open,
            { gl ->
                check(gl !is LwjglGles) { "The passes do not link over OpenGL ES here" }
                Passes(gl)
            },
        ) { reasons += it.message }
        try {
            assertEquals(listOf<String?>("The passes do not link over OpenGL ES here"), reasons)
            assertIs<LwjglGl>(context.gl)
            val frame = frameOf(pattern(16, 8))
            passes.upload(frame.width, frame.height, frame.pixels)
            assertEquals(16, passes.frameWidth)
        } finally {
            passes.release()
            context.close()
        }
    }

    /** The window's own choice: ANGLE's OpenGL ES on Windows, EGL's on the GPU elsewhere. */
    @Test
    fun theSystemsContextIsOpenGlEs() {
        val (context, passes) = GlContext.open { gl -> Passes(gl) }
        try {
            println("The system's context: ${context.renderer}, ${context.version}")
            assertIs<LwjglGles>(context.gl)
        } finally {
            passes.release()
            context.close()
        }
    }
}
