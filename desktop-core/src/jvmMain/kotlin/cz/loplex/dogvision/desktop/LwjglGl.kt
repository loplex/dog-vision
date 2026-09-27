package cz.loplex.dogvision.desktop

import org.lwjgl.opengl.GL33C.GL_ALREADY_SIGNALED
import org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL33C.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL33C.GL_CONDITION_SATISFIED
import org.lwjgl.opengl.GL33C.GL_LINK_STATUS
import org.lwjgl.opengl.GL33C.GL_MAP_READ_BIT
import org.lwjgl.opengl.GL33C.GL_RGBA
import org.lwjgl.opengl.GL33C.GL_SYNC_GPU_COMMANDS_COMPLETE
import org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL33C.glActiveTexture
import org.lwjgl.opengl.GL33C.glAttachShader
import org.lwjgl.opengl.GL33C.glBindBuffer
import org.lwjgl.opengl.GL33C.glBindFramebuffer
import org.lwjgl.opengl.GL33C.glBindTexture
import org.lwjgl.opengl.GL33C.glBindVertexArray
import org.lwjgl.opengl.GL33C.glBufferData
import org.lwjgl.opengl.GL33C.glCheckFramebufferStatus
import org.lwjgl.opengl.GL33C.glClear
import org.lwjgl.opengl.GL33C.glClearColor
import org.lwjgl.opengl.GL33C.glClientWaitSync
import org.lwjgl.opengl.GL33C.glCompileShader
import org.lwjgl.opengl.GL33C.glCreateProgram
import org.lwjgl.opengl.GL33C.glCreateShader
import org.lwjgl.opengl.GL33C.glDeleteBuffers
import org.lwjgl.opengl.GL33C.glDeleteFramebuffers
import org.lwjgl.opengl.GL33C.glDeleteProgram
import org.lwjgl.opengl.GL33C.glDeleteShader
import org.lwjgl.opengl.GL33C.glDeleteSync
import org.lwjgl.opengl.GL33C.glDeleteTextures
import org.lwjgl.opengl.GL33C.glDeleteVertexArrays
import org.lwjgl.opengl.GL33C.glDisable
import org.lwjgl.opengl.GL33C.glDrawArrays
import org.lwjgl.opengl.GL33C.glFenceSync
import org.lwjgl.opengl.GL33C.glFlush
import org.lwjgl.opengl.GL33C.glFramebufferTexture2D
import org.lwjgl.opengl.GL33C.glGenBuffers
import org.lwjgl.opengl.GL33C.glGenFramebuffers
import org.lwjgl.opengl.GL33C.glGenTextures
import org.lwjgl.opengl.GL33C.glGenVertexArrays
import org.lwjgl.opengl.GL33C.glGetProgramInfoLog
import org.lwjgl.opengl.GL33C.glGetProgrami
import org.lwjgl.opengl.GL33C.glGetShaderInfoLog
import org.lwjgl.opengl.GL33C.glGetShaderi
import org.lwjgl.opengl.GL33C.glGetUniformLocation
import org.lwjgl.opengl.GL33C.glLinkProgram
import org.lwjgl.opengl.GL33C.glMapBufferRange
import org.lwjgl.opengl.GL33C.glPixelStorei
import org.lwjgl.opengl.GL33C.glReadPixels
import org.lwjgl.opengl.GL33C.glShaderSource
import org.lwjgl.opengl.GL33C.glTexImage2D
import org.lwjgl.opengl.GL33C.glTexParameteri
import org.lwjgl.opengl.GL33C.glUniform1f
import org.lwjgl.opengl.GL33C.glUniform1i
import org.lwjgl.opengl.GL33C.glUniform2i
import org.lwjgl.opengl.GL33C.glUniform4f
import org.lwjgl.opengl.GL33C.glUniformMatrix3fv
import org.lwjgl.opengl.GL33C.glUnmapBuffer
import org.lwjgl.opengl.GL33C.glUseProgram
import org.lwjgl.opengl.GL33C.glViewport
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * gl's Gl over desktop OpenGL 3.3's core profile through LWJGL, in the context current on the calling thread, as
 * [LwjglGles] is over OpenGL ES 3.0, for the WGL context on Windows. gl's shaders are GLSL ES 3.00, which desktop GLSL
 * 3.30 reads as it is but for its first line, so that line is rewritten; precision qualifiers are allowed there and
 * mean nothing, and desktop GL's floats are as wide as ES's highp ones.
 */
class LwjglGl : DesktopGl {
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0)

    /** A direct buffer of [size] bytes, from the start, reused while it is large enough. */
    private fun scratch(size: Int): ByteBuffer {
        if (scratch.capacity() < size) scratch = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        return scratch.clear().limit(size)
    }

    override fun createProgram() = glCreateProgram()

    override fun createShader(type: Int) = glCreateShader(type)

    override fun shaderSource(shader: Int, source: String) = glShaderSource(shader, desktopShader(source))

    override fun compileShader(shader: Int) = glCompileShader(shader)

    override fun shaderCompiled(shader: Int) = glGetShaderi(shader, GL_COMPILE_STATUS) != 0

    override fun shaderInfoLog(shader: Int): String = glGetShaderInfoLog(shader)

    override fun attachShader(program: Int, shader: Int) = glAttachShader(program, shader)

    override fun linkProgram(program: Int) = glLinkProgram(program)

    override fun programLinked(program: Int) = glGetProgrami(program, GL_LINK_STATUS) != 0

    override fun programInfoLog(program: Int): String = glGetProgramInfoLog(program)

    override fun deleteShader(shader: Int) = glDeleteShader(shader)

    override fun deleteProgram(program: Int) = glDeleteProgram(program)

    override fun useProgram(program: Int) = glUseProgram(program)

    override fun uniformLocation(program: Int, name: String) = glGetUniformLocation(program, name)

    override fun uniform1i(location: Int, x: Int) = glUniform1i(location, x)

    override fun uniform2i(location: Int, x: Int, y: Int) = glUniform2i(location, x, y)

    override fun uniform1f(location: Int, x: Float) = glUniform1f(location, x)

    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) = glUniform4f(location, x, y, z, w)

    override fun uniformMatrix3fv(location: Int, transpose: Boolean, values: FloatArray) =
        glUniformMatrix3fv(location, transpose, values)

    override fun createTexture() = glGenTextures()

    override fun deleteTexture(texture: Int) = glDeleteTextures(texture)

    override fun activeTexture(unit: Int) = glActiveTexture(unit)

    override fun bindTexture(target: Int, texture: Int) = glBindTexture(target, texture)

    override fun texParameteri(target: Int, name: Int, value: Int) = glTexParameteri(target, name, value)

    override fun texImage2D(
        target: Int,
        level: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: ByteArray?,
    ) = glTexImage2D(
        target,
        level,
        internalFormat,
        width,
        height,
        0,
        format,
        type,
        pixels?.let { scratch(it.size).put(it).flip() },
    )

    override fun texImage2D(
        target: Int,
        level: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: ByteBuffer,
    ) = glTexImage2D(target, level, internalFormat, width, height, 0, format, type, pixels)

    override fun texImage2D(
        target: Int,
        level: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: FloatArray,
    ) = glTexImage2D(target, level, internalFormat, width, height, 0, format, type, pixels)

    override fun pixelStorei(name: Int, value: Int) = glPixelStorei(name, value)

    override fun createFramebuffer() = glGenFramebuffers()

    override fun deleteFramebuffer(framebuffer: Int) = glDeleteFramebuffers(framebuffer)

    override fun bindFramebuffer(target: Int, framebuffer: Int) = glBindFramebuffer(target, framebuffer)

    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) =
        glFramebufferTexture2D(target, attachment, textureTarget, texture, level)

    override fun checkFramebufferStatus(target: Int) = glCheckFramebufferStatus(target)

    override fun createVertexArray() = glGenVertexArrays()

    override fun deleteVertexArray(array: Int) = glDeleteVertexArrays(array)

    override fun bindVertexArray(array: Int) = glBindVertexArray(array)

    override fun createBuffer() = glGenBuffers()

    override fun deleteBuffer(buffer: Int) = glDeleteBuffers(buffer)

    override fun bindBuffer(target: Int, buffer: Int) = glBindBuffer(target, buffer)

    override fun bufferData(target: Int, size: Int, usage: Int) = glBufferData(target, size.toLong(), usage)

    override fun bufferSubData(target: Int, size: Int): ByteArray = readMapped(target, size) {
        ByteArray(size).also(it::get)
    }

    override fun <T> readMapped(target: Int, size: Int, read: (ByteBuffer) -> T): T {
        val mapped = checkNotNull(glMapBufferRange(target, 0, size.toLong(), GL_MAP_READ_BIT)) {
            "Cannot map a buffer to read it"
        }
        try {
            return read(mapped)
        } finally {
            glUnmapBuffer(target)
        }
    }

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = glViewport(x, y, width, height)

    override fun disable(capability: Int) = glDisable(capability)

    override fun drawArrays(mode: Int, first: Int, count: Int) = glDrawArrays(mode, first, count)

    override fun readPixels(x: Int, y: Int, width: Int, height: Int, pixels: ByteArray) {
        val buffer = scratch(pixels.size)
        glReadPixels(x, y, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        buffer.get(pixels)
    }

    override fun readPixelsIntoPackBuffer(x: Int, y: Int, width: Int, height: Int) =
        glReadPixels(x, y, width, height, GL_RGBA, GL_UNSIGNED_BYTE, 0L)

    override fun fenceSync() = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)

    override fun signalled(sync: Long): Boolean {
        val status = glClientWaitSync(sync, 0, 0)
        return status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED
    }

    override fun deleteSync(sync: Long) = glDeleteSync(sync)

    override fun flush() = glFlush()

    override fun clear() {
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT)
    }
}

/** [source], a GLSL ES 3.00 shader, as desktop GLSL 3.30, which differs from it in its first line alone. */
internal fun desktopShader(source: String): String {
    check(source.startsWith(ES_VERSION)) { "Not a GLSL ES 3.00 shader: ${source.lineSequence().first()}" }
    return DESKTOP_VERSION + source.removePrefix(ES_VERSION)
}

private const val ES_VERSION = "#version 300 es\n"
private const val DESKTOP_VERSION = "#version 330 core\n"
