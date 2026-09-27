package cz.loplex.dogvision.render

import android.opengl.GLES30.GL_ALREADY_SIGNALED
import android.opengl.GLES30.GL_COMPILE_STATUS
import android.opengl.GLES30.GL_CONDITION_SATISFIED
import android.opengl.GLES30.GL_LINK_STATUS
import android.opengl.GLES30.GL_MAP_READ_BIT
import android.opengl.GLES30.GL_RGBA
import android.opengl.GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE
import android.opengl.GLES30.GL_UNSIGNED_BYTE
import android.opengl.GLES30.glActiveTexture
import android.opengl.GLES30.glAttachShader
import android.opengl.GLES30.glBindBuffer
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glBindTexture
import android.opengl.GLES30.glBindVertexArray
import android.opengl.GLES30.glBufferData
import android.opengl.GLES30.glCheckFramebufferStatus
import android.opengl.GLES30.glClientWaitSync
import android.opengl.GLES30.glCompileShader
import android.opengl.GLES30.glCreateProgram
import android.opengl.GLES30.glCreateShader
import android.opengl.GLES30.glDeleteBuffers
import android.opengl.GLES30.glDeleteFramebuffers
import android.opengl.GLES30.glDeleteProgram
import android.opengl.GLES30.glDeleteShader
import android.opengl.GLES30.glDeleteSync
import android.opengl.GLES30.glDeleteTextures
import android.opengl.GLES30.glDeleteVertexArrays
import android.opengl.GLES30.glDisable
import android.opengl.GLES30.glDrawArrays
import android.opengl.GLES30.glFenceSync
import android.opengl.GLES30.glFlush
import android.opengl.GLES30.glFramebufferTexture2D
import android.opengl.GLES30.glGenBuffers
import android.opengl.GLES30.glGenFramebuffers
import android.opengl.GLES30.glGenTextures
import android.opengl.GLES30.glGenVertexArrays
import android.opengl.GLES30.glGetProgramInfoLog
import android.opengl.GLES30.glGetProgramiv
import android.opengl.GLES30.glGetShaderInfoLog
import android.opengl.GLES30.glGetShaderiv
import android.opengl.GLES30.glGetUniformLocation
import android.opengl.GLES30.glLinkProgram
import android.opengl.GLES30.glMapBufferRange
import android.opengl.GLES30.glPixelStorei
import android.opengl.GLES30.glReadPixels
import android.opengl.GLES30.glShaderSource
import android.opengl.GLES30.glTexImage2D
import android.opengl.GLES30.glTexParameteri
import android.opengl.GLES30.glUniform1f
import android.opengl.GLES30.glUniform1i
import android.opengl.GLES30.glUniform2i
import android.opengl.GLES30.glUniform4f
import android.opengl.GLES30.glUniformMatrix3fv
import android.opengl.GLES30.glUnmapBuffer
import android.opengl.GLES30.glUseProgram
import android.opengl.GLES30.glViewport
import cz.loplex.dogvision.gl.Gl
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * gl's [Gl] over OpenGL ES 3.0, in the context current on the calling thread. GL's names are its own; pixels are
 * copied through direct buffers, which Android's GL bindings take.
 */
internal object Gles : Gl {
    override fun createProgram() = glCreateProgram()

    override fun createShader(type: Int) = glCreateShader(type)

    override fun shaderSource(shader: Int, source: String) = glShaderSource(shader, source)

    override fun compileShader(shader: Int) = glCompileShader(shader)

    override fun shaderCompiled(shader: Int): Boolean {
        val status = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0)
        return status[0] != 0
    }

    override fun shaderInfoLog(shader: Int): String = glGetShaderInfoLog(shader)

    override fun attachShader(program: Int, shader: Int) = glAttachShader(program, shader)

    override fun linkProgram(program: Int) = glLinkProgram(program)

    override fun programLinked(program: Int): Boolean {
        val status = IntArray(1)
        glGetProgramiv(program, GL_LINK_STATUS, status, 0)
        return status[0] != 0
    }

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
        glUniformMatrix3fv(location, 1, transpose, values, 0)

    override fun createTexture() = IntArray(1).also { glGenTextures(1, it, 0) }[0]

    override fun deleteTexture(texture: Int) = glDeleteTextures(1, intArrayOf(texture), 0)

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
    ) = glTexImage2D(target, level, internalFormat, width, height, 0, format, type, pixels?.let(::direct))

    override fun texImage2D(
        target: Int,
        level: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: FloatArray,
    ) {
        val buffer = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(pixels)
        glTexImage2D(target, level, internalFormat, width, height, 0, format, type, buffer)
    }

    override fun pixelStorei(name: Int, value: Int) = glPixelStorei(name, value)

    override fun createFramebuffer() = IntArray(1).also { glGenFramebuffers(1, it, 0) }[0]

    override fun deleteFramebuffer(framebuffer: Int) = glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)

    override fun bindFramebuffer(target: Int, framebuffer: Int) = glBindFramebuffer(target, framebuffer)

    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) =
        glFramebufferTexture2D(target, attachment, textureTarget, texture, level)

    override fun checkFramebufferStatus(target: Int) = glCheckFramebufferStatus(target)

    override fun createVertexArray() = IntArray(1).also { glGenVertexArrays(1, it, 0) }[0]

    override fun deleteVertexArray(array: Int) = glDeleteVertexArrays(1, intArrayOf(array), 0)

    override fun bindVertexArray(array: Int) = glBindVertexArray(array)

    override fun createBuffer() = IntArray(1).also { glGenBuffers(1, it, 0) }[0]

    override fun deleteBuffer(buffer: Int) = glDeleteBuffers(1, intArrayOf(buffer), 0)

    override fun bindBuffer(target: Int, buffer: Int) = glBindBuffer(target, buffer)

    override fun bufferData(target: Int, size: Int, usage: Int) = glBufferData(target, size, null, usage)

    override fun bufferSubData(target: Int, size: Int): ByteArray {
        val mapped = glMapBufferRange(target, 0, size, GL_MAP_READ_BIT) as ByteBuffer
        val bytes = ByteArray(size).also { mapped.get(it) }
        glUnmapBuffer(target)
        return bytes
    }

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = glViewport(x, y, width, height)

    override fun disable(capability: Int) = glDisable(capability)

    override fun drawArrays(mode: Int, first: Int, count: Int) = glDrawArrays(mode, first, count)

    override fun readPixels(x: Int, y: Int, width: Int, height: Int, pixels: ByteArray) {
        val buffer = ByteBuffer.allocateDirect(pixels.size)
        glReadPixels(x, y, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        buffer.get(pixels)
    }

    override fun readPixelsIntoPackBuffer(x: Int, y: Int, width: Int, height: Int) =
        glReadPixels(x, y, width, height, GL_RGBA, GL_UNSIGNED_BYTE, 0)

    override fun fenceSync() = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)

    override fun signalled(sync: Long): Boolean {
        val status = glClientWaitSync(sync, 0, 0)
        return status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED
    }

    override fun deleteSync(sync: Long) = glDeleteSync(sync)

    override fun flush() = glFlush()

    private fun direct(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
}
