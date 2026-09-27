package cz.loplex.dogvision.web

import cz.loplex.dogvision.gl.GL_BLEND
import cz.loplex.dogvision.gl.GL_CLAMP_TO_EDGE
import cz.loplex.dogvision.gl.GL_COLOR_ATTACHMENT0
import cz.loplex.dogvision.gl.GL_DEPTH_TEST
import cz.loplex.dogvision.gl.GL_FLOAT
import cz.loplex.dogvision.gl.GL_FRAGMENT_SHADER
import cz.loplex.dogvision.gl.GL_FRAMEBUFFER
import cz.loplex.dogvision.gl.GL_FRAMEBUFFER_COMPLETE
import cz.loplex.dogvision.gl.GL_LINEAR
import cz.loplex.dogvision.gl.GL_NEAREST
import cz.loplex.dogvision.gl.GL_PIXEL_PACK_BUFFER
import cz.loplex.dogvision.gl.GL_R32F
import cz.loplex.dogvision.gl.GL_R8
import cz.loplex.dogvision.gl.GL_RED
import cz.loplex.dogvision.gl.GL_RGBA
import cz.loplex.dogvision.gl.GL_RGBA8
import cz.loplex.dogvision.gl.GL_SCISSOR_TEST
import cz.loplex.dogvision.gl.GL_STREAM_READ
import cz.loplex.dogvision.gl.GL_TEXTURE0
import cz.loplex.dogvision.gl.GL_TEXTURE_2D
import cz.loplex.dogvision.gl.GL_TEXTURE_MAG_FILTER
import cz.loplex.dogvision.gl.GL_TEXTURE_MIN_FILTER
import cz.loplex.dogvision.gl.GL_TEXTURE_WRAP_S
import cz.loplex.dogvision.gl.GL_TEXTURE_WRAP_T
import cz.loplex.dogvision.gl.GL_TRIANGLES
import cz.loplex.dogvision.gl.GL_TRIANGLE_STRIP
import cz.loplex.dogvision.gl.GL_UNPACK_ALIGNMENT
import cz.loplex.dogvision.gl.GL_UNSIGNED_BYTE
import cz.loplex.dogvision.gl.GL_VERTEX_SHADER
import kotlin.test.Test
import kotlin.test.assertEquals

/** gl's values of the GL enums are WebGL 2's, which takes them from OpenGL ES, whose values the app passes on. */
class GlConstantsTest {
    @Test
    fun everyValueIsWebGl2s() {
        // The constants of the interface itself, which need no context: making one can take seconds in software.
        val gl: dynamic = js("WebGL2RenderingContext")
        val values = listOf(
            "TRIANGLES" to GL_TRIANGLES,
            "TRIANGLE_STRIP" to GL_TRIANGLE_STRIP,
            "DEPTH_TEST" to GL_DEPTH_TEST,
            "BLEND" to GL_BLEND,
            "SCISSOR_TEST" to GL_SCISSOR_TEST,
            "UNPACK_ALIGNMENT" to GL_UNPACK_ALIGNMENT,
            "TEXTURE_2D" to GL_TEXTURE_2D,
            "UNSIGNED_BYTE" to GL_UNSIGNED_BYTE,
            "FLOAT" to GL_FLOAT,
            "RED" to GL_RED,
            "RGBA" to GL_RGBA,
            "NEAREST" to GL_NEAREST,
            "LINEAR" to GL_LINEAR,
            "TEXTURE_MAG_FILTER" to GL_TEXTURE_MAG_FILTER,
            "TEXTURE_MIN_FILTER" to GL_TEXTURE_MIN_FILTER,
            "TEXTURE_WRAP_S" to GL_TEXTURE_WRAP_S,
            "TEXTURE_WRAP_T" to GL_TEXTURE_WRAP_T,
            "RGBA8" to GL_RGBA8,
            "CLAMP_TO_EDGE" to GL_CLAMP_TO_EDGE,
            "R8" to GL_R8,
            "R32F" to GL_R32F,
            "TEXTURE0" to GL_TEXTURE0,
            "STREAM_READ" to GL_STREAM_READ,
            "PIXEL_PACK_BUFFER" to GL_PIXEL_PACK_BUFFER,
            "FRAGMENT_SHADER" to GL_FRAGMENT_SHADER,
            "VERTEX_SHADER" to GL_VERTEX_SHADER,
            "FRAMEBUFFER_COMPLETE" to GL_FRAMEBUFFER_COMPLETE,
            "COLOR_ATTACHMENT0" to GL_COLOR_ATTACHMENT0,
            "FRAMEBUFFER" to GL_FRAMEBUFFER,
        )
        values.forEach { (name, value) -> assertEquals(gl[name] as Int, value, name) }
    }
}
