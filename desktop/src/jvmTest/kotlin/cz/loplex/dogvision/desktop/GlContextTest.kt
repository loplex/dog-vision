package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.testing.pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Contexts of OpenGL ES and of desktop OpenGL open one after the other in one JVM, though LWJGL takes the functions of
 * only one of the two APIs at a time, and a context falls back to another where it cannot be prepared, as the window
 * falls back from ANGLE's OpenGL ES to WGL's desktop OpenGL; EGL's desktop OpenGL stands in for WGL's here.
 */
class GlContextTest {
    @Test
    fun eitherApiOpensAfterTheOther() {
        for (api in listOf(EglContext.Api.ES, EglContext.Api.DESKTOP, EglContext.Api.ES)) {
            EglContext.onDevice(api).use { context ->
                Passes(context.gl).release()
                assertTrue(context.version.isNotEmpty(), "${api.title} names no version")
            }
        }
    }

    @Test
    fun theSecondContextDrawsWhereTheFirstCannotBePrepared() {
        val reasons = mutableListOf<String?>()
        val (context, passes) = GlContext.fallingBack(
            { EglContext.onDevice(EglContext.Api.ES) },
            { EglContext.onDevice(EglContext.Api.DESKTOP) },
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
}
