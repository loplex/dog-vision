package cz.loplex.dogvision.web

import cz.loplex.dogvision.gl.Gl
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.WebGLBuffer
import org.khronos.webgl.WebGLFramebuffer
import org.khronos.webgl.WebGLProgram
import org.khronos.webgl.WebGLRenderingContext.Companion.COMPILE_STATUS
import org.khronos.webgl.WebGLRenderingContext.Companion.LINK_STATUS
import org.khronos.webgl.WebGLRenderingContext.Companion.RGBA
import org.khronos.webgl.WebGLRenderingContext.Companion.UNSIGNED_BYTE
import org.khronos.webgl.WebGLShader
import org.khronos.webgl.WebGLTexture
import org.khronos.webgl.WebGLUniformLocation

/**
 * The passes' [Gl] over a WebGL 2 context, which names its objects by integers as OpenGL ES does: each object WebGL
 * makes gets the next name in a table, and 0 stands for none. A name is not used again once its object is deleted.
 *
 * A Kotlin ByteArray is an Int8Array and a FloatArray a Float32Array in JavaScript, so pixels cross without a copy,
 * through an Uint8Array over the same memory where WebGL wants one.
 */
internal class WebGl(private val gl: WebGL2RenderingContext) : Gl {
    private val objects = mutableListOf<Any?>(null)

    private fun name(obj: Any?): Int {
        if (obj == null) return 0
        objects.add(obj)
        return objects.lastIndex
    }

    private fun <T> obj(name: Int): T? = objects[name]?.unsafeCast<T>()

    private fun forget(name: Int) {
        if (name != 0) objects[name] = null
    }

    override fun createProgram() = name(gl.createProgram())

    override fun createShader(type: Int) = name(gl.createShader(type))

    override fun shaderSource(shader: Int, source: String) = gl.shaderSource(obj<WebGLShader>(shader), source)

    override fun compileShader(shader: Int) = gl.compileShader(obj<WebGLShader>(shader))

    override fun shaderCompiled(shader: Int) = gl.getShaderParameter(obj<WebGLShader>(shader), COMPILE_STATUS) == true

    override fun shaderInfoLog(shader: Int) = gl.getShaderInfoLog(obj<WebGLShader>(shader)).orEmpty()

    override fun attachShader(program: Int, shader: Int) =
        gl.attachShader(obj<WebGLProgram>(program), obj<WebGLShader>(shader))

    override fun linkProgram(program: Int) = gl.linkProgram(obj<WebGLProgram>(program))

    override fun programLinked(program: Int) = gl.getProgramParameter(obj<WebGLProgram>(program), LINK_STATUS) == true

    override fun programInfoLog(program: Int) = gl.getProgramInfoLog(obj<WebGLProgram>(program)).orEmpty()

    override fun deleteShader(shader: Int) {
        gl.deleteShader(obj<WebGLShader>(shader))
        forget(shader)
    }

    override fun deleteProgram(program: Int) {
        gl.deleteProgram(obj<WebGLProgram>(program))
        forget(program)
    }

    override fun useProgram(program: Int) = gl.useProgram(obj<WebGLProgram>(program))

    override fun uniformLocation(program: Int, name: String): Int =
        gl.getUniformLocation(obj<WebGLProgram>(program), name)?.let(::name) ?: -1

    private fun location(location: Int): WebGLUniformLocation? = if (location < 0) null else obj(location)

    override fun uniform1i(location: Int, x: Int) = gl.uniform1i(location(location), x)

    override fun uniform2i(location: Int, x: Int, y: Int) = gl.uniform2i(location(location), x, y)

    override fun uniform1f(location: Int, x: Float) = gl.uniform1f(location(location), x)

    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) =
        gl.uniform4f(location(location), x, y, z, w)

    override fun uniformMatrix3fv(location: Int, transpose: Boolean, values: FloatArray) =
        gl.uniformMatrix3fv(location(location), transpose, values.unsafeCast<Float32Array>())

    override fun createTexture() = name(gl.createTexture())

    override fun deleteTexture(texture: Int) {
        gl.deleteTexture(obj<WebGLTexture>(texture))
        forget(texture)
    }

    override fun activeTexture(unit: Int) = gl.activeTexture(unit)

    override fun bindTexture(target: Int, texture: Int) = gl.bindTexture(target, obj<WebGLTexture>(texture))

    override fun texParameteri(target: Int, name: Int, value: Int) = gl.texParameteri(target, name, value)

    override fun texImage2D(
        target: Int,
        level: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: ByteArray?,
    ) = gl.texImage2D(target, level, internalFormat, width, height, 0, format, type, pixels?.let(::unsigned))

    override fun texImage2D(
        target: Int,
        level: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: FloatArray,
    ) = gl.texImage2D(target, level, internalFormat, width, height, 0, format, type, pixels.unsafeCast<Float32Array>())

    override fun pixelStorei(name: Int, value: Int) = gl.pixelStorei(name, value)

    override fun createFramebuffer() = name(gl.createFramebuffer())

    override fun deleteFramebuffer(framebuffer: Int) {
        gl.deleteFramebuffer(obj<WebGLFramebuffer>(framebuffer))
        forget(framebuffer)
    }

    override fun bindFramebuffer(target: Int, framebuffer: Int) =
        gl.bindFramebuffer(target, obj<WebGLFramebuffer>(framebuffer))

    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) =
        gl.framebufferTexture2D(target, attachment, textureTarget, obj<WebGLTexture>(texture), level)

    override fun checkFramebufferStatus(target: Int) = gl.checkFramebufferStatus(target)

    override fun createVertexArray() = name(gl.createVertexArray())

    override fun deleteVertexArray(array: Int) {
        gl.deleteVertexArray(obj<WebGLVertexArrayObject>(array))
        forget(array)
    }

    override fun bindVertexArray(array: Int) = gl.bindVertexArray(obj<WebGLVertexArrayObject>(array))

    override fun createBuffer() = name(gl.createBuffer())

    override fun deleteBuffer(buffer: Int) {
        gl.deleteBuffer(obj<WebGLBuffer>(buffer))
        forget(buffer)
    }

    override fun bindBuffer(target: Int, buffer: Int) = gl.bindBuffer(target, obj<WebGLBuffer>(buffer))

    override fun bufferData(target: Int, size: Int, usage: Int) = gl.bufferData(target, size, usage)

    override fun bufferSubData(target: Int, size: Int): ByteArray =
        ByteArray(size).also { gl.getBufferSubData(target, 0, unsigned(it)) }

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = gl.viewport(x, y, width, height)

    override fun disable(capability: Int) = gl.disable(capability)

    override fun drawArrays(mode: Int, first: Int, count: Int) = gl.drawArrays(mode, first, count)

    override fun readPixels(x: Int, y: Int, width: Int, height: Int, pixels: ByteArray) =
        gl.readPixels(x, y, width, height, RGBA, UNSIGNED_BYTE, unsigned(pixels))

    override fun readPixelsIntoPackBuffer(x: Int, y: Int, width: Int, height: Int) =
        gl.readPixels(x, y, width, height, RGBA, UNSIGNED_BYTE, 0)

    override fun fenceSync(): Long = name(gl.fenceSync(Gl2.SYNC_GPU_COMMANDS_COMPLETE, 0)).toLong()

    override fun signalled(sync: Long): Boolean {
        val status = gl.clientWaitSync(obj<WebGLSync>(sync.toInt()), 0, 0)
        return status == Gl2.ALREADY_SIGNALED || status == Gl2.CONDITION_SATISFIED
    }

    override fun deleteSync(sync: Long) {
        gl.deleteSync(obj<WebGLSync>(sync.toInt()))
        forget(sync.toInt())
    }

    override fun flush() = gl.flush()

    /** The bytes of [array] as the Uint8Array WebGL takes for unsigned bytes, over the same memory. */
    private fun unsigned(array: ByteArray): Uint8Array {
        val bytes = array.unsafeCast<Int8Array>()
        return Uint8Array(bytes.buffer, bytes.byteOffset, bytes.length)
    }
}
