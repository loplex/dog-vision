package cz.loplex.dogvision.desktop

import org.lwjgl.opengles.GLES30.GL_ALREADY_SIGNALED
import org.lwjgl.opengles.GLES30.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengles.GLES30.GL_COMPILE_STATUS
import org.lwjgl.opengles.GLES30.GL_CONDITION_SATISFIED
import org.lwjgl.opengles.GLES30.GL_LINK_STATUS
import org.lwjgl.opengles.GLES30.GL_MAP_READ_BIT
import org.lwjgl.opengles.GLES30.GL_RGBA
import org.lwjgl.opengles.GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE
import org.lwjgl.opengles.GLES30.GL_UNSIGNED_BYTE
import org.lwjgl.opengles.GLES30.glActiveTexture
import org.lwjgl.opengles.GLES30.glAttachShader
import org.lwjgl.opengles.GLES30.glBindBuffer
import org.lwjgl.opengles.GLES30.glBindFramebuffer
import org.lwjgl.opengles.GLES30.glBindTexture
import org.lwjgl.opengles.GLES30.glBindVertexArray
import org.lwjgl.opengles.GLES30.glBufferData
import org.lwjgl.opengles.GLES30.glCheckFramebufferStatus
import org.lwjgl.opengles.GLES30.glClear
import org.lwjgl.opengles.GLES30.glClearColor
import org.lwjgl.opengles.GLES30.glClientWaitSync
import org.lwjgl.opengles.GLES30.glCompileShader
import org.lwjgl.opengles.GLES30.glCreateProgram
import org.lwjgl.opengles.GLES30.glCreateShader
import org.lwjgl.opengles.GLES30.glDeleteBuffers
import org.lwjgl.opengles.GLES30.glDeleteFramebuffers
import org.lwjgl.opengles.GLES30.glDeleteProgram
import org.lwjgl.opengles.GLES30.glDeleteShader
import org.lwjgl.opengles.GLES30.glDeleteSync
import org.lwjgl.opengles.GLES30.glDeleteTextures
import org.lwjgl.opengles.GLES30.glDeleteVertexArrays
import org.lwjgl.opengles.GLES30.glDisable
import org.lwjgl.opengles.GLES30.glDrawArrays
import org.lwjgl.opengles.GLES30.glFenceSync
import org.lwjgl.opengles.GLES30.glFlush
import org.lwjgl.opengles.GLES30.glFramebufferTexture2D
import org.lwjgl.opengles.GLES30.glGenBuffers
import org.lwjgl.opengles.GLES30.glGenFramebuffers
import org.lwjgl.opengles.GLES30.glGenTextures
import org.lwjgl.opengles.GLES30.glGenVertexArrays
import org.lwjgl.opengles.GLES30.glGetProgramInfoLog
import org.lwjgl.opengles.GLES30.glGetProgrami
import org.lwjgl.opengles.GLES30.glGetShaderInfoLog
import org.lwjgl.opengles.GLES30.glGetShaderi
import org.lwjgl.opengles.GLES30.glGetUniformLocation
import org.lwjgl.opengles.GLES30.glLinkProgram
import org.lwjgl.opengles.GLES30.glMapBufferRange
import org.lwjgl.opengles.GLES30.glPixelStorei
import org.lwjgl.opengles.GLES30.glReadPixels
import org.lwjgl.opengles.GLES30.glShaderSource
import org.lwjgl.opengles.GLES30.glTexImage2D
import org.lwjgl.opengles.GLES30.glTexParameteri
import org.lwjgl.opengles.GLES30.glUniform1f
import org.lwjgl.opengles.GLES30.glUniform1i
import org.lwjgl.opengles.GLES30.glUniform2i
import org.lwjgl.opengles.GLES30.glUniform4f
import org.lwjgl.opengles.GLES30.glUniformMatrix3fv
import org.lwjgl.opengles.GLES30.glUnmapBuffer
import org.lwjgl.opengles.GLES30.glUseProgram
import org.lwjgl.opengles.GLES30.glViewport
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * gl's Gl over OpenGL ES 3.0 through LWJGL, in the context current on the calling thread, as the Android app's
 * `Gles` is over Android's bindings. LWJGL takes pixels only in direct buffers, so they are copied through one kept
 * for the purpose; [readPixels] and [texImage2D] take a direct buffer as well, for a frame that is one already.
 */
class LwjglGles : DesktopGl {
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0)

    /** A direct buffer of [size] bytes, from the start, reused while it is large enough. */
    private fun scratch(size: Int): ByteBuffer {
        if (scratch.capacity() < size) scratch = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        return scratch.clear().limit(size)
    }

    override fun createProgram() = glCreateProgram()

    override fun createShader(type: Int) = glCreateShader(type)

    override fun shaderSource(shader: Int, source: String) = glShaderSource(shader, source)

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

    /** Reads RGBA bytes of the framebuffer bound into the direct buffer [pixels], from its position on. */
    fun readPixels(x: Int, y: Int, width: Int, height: Int, pixels: ByteBuffer) =
        glReadPixels(x, y, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)

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
