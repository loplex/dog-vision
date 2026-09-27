package cz.loplex.dogvision.gl

/**
 * The part of OpenGL ES 3.0 the passes call, which WebGL 2 has as well, so that one copy of them serves the Android app
 * and the web page.
 *
 * GL objects are named by integers, as in OpenGL ES, with 0 for none; an implementation over WebGL 2, whose objects
 * are JavaScript objects, keeps a table of them, as Emscripten's GL layer does. Pixels cross it as arrays, which an
 * implementation copies where its platform wants a buffer of its own. Every call runs on the thread whose context is
 * current, as GL's own do.
 */
interface Gl {
    fun createProgram(): Int

    fun createShader(type: Int): Int

    fun shaderSource(shader: Int, source: String)

    fun compileShader(shader: Int)

    fun shaderCompiled(shader: Int): Boolean

    fun shaderInfoLog(shader: Int): String

    fun attachShader(program: Int, shader: Int)

    fun linkProgram(program: Int)

    fun programLinked(program: Int): Boolean

    fun programInfoLog(program: Int): String

    fun deleteShader(shader: Int)

    fun deleteProgram(program: Int)

    fun useProgram(program: Int)

    /** The uniform's location, or -1 where the program has none of that name. */
    fun uniformLocation(program: Int, name: String): Int

    fun uniform1i(location: Int, x: Int)

    fun uniform2i(location: Int, x: Int, y: Int)

    fun uniform1f(location: Int, x: Float)

    fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float)

    /** Sets a mat3 from [values], nine floats row after row if [transpose], else column after column. */
    fun uniformMatrix3fv(location: Int, transpose: Boolean, values: FloatArray)

    fun createTexture(): Int

    fun deleteTexture(texture: Int)

    fun activeTexture(unit: Int)

    fun bindTexture(target: Int, texture: Int)

    fun texParameteri(target: Int, name: Int, value: Int)

    /** Allocates the texture bound, filled from [pixels], or left undefined if null. */
    fun texImage2D(
        target: Int,
        level: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: ByteArray?,
    )

    /** Allocates the texture bound, filled from [pixels], a float per channel. */
    fun texImage2D(
        target: Int,
        level: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: FloatArray,
    )

    fun pixelStorei(name: Int, value: Int)

    fun createFramebuffer(): Int

    fun deleteFramebuffer(framebuffer: Int)

    fun bindFramebuffer(target: Int, framebuffer: Int)

    fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int)

    fun checkFramebufferStatus(target: Int): Int

    fun createVertexArray(): Int

    fun deleteVertexArray(array: Int)

    fun bindVertexArray(array: Int)

    fun createBuffer(): Int

    fun deleteBuffer(buffer: Int)

    fun bindBuffer(target: Int, buffer: Int)

    /** Allocates [size] bytes, undefined, for the buffer bound to [target]. */
    fun bufferData(target: Int, size: Int, usage: Int)

    /** The first [size] bytes of the buffer bound to [target], read back from the GPU. */
    fun bufferSubData(target: Int, size: Int): ByteArray

    fun viewport(x: Int, y: Int, width: Int, height: Int)

    fun disable(capability: Int)

    fun drawArrays(mode: Int, first: Int, count: Int)

    /** Reads RGBA bytes of the framebuffer bound into [pixels]. */
    fun readPixels(x: Int, y: Int, width: Int, height: Int, pixels: ByteArray)

    /** Reads RGBA bytes of the framebuffer bound into the pixel pack buffer bound, from its start, without waiting. */
    fun readPixelsIntoPackBuffer(x: Int, y: Int, width: Int, height: Int)

    /** A fence the GPU signals once it has done every command given before it; not 0. */
    fun fenceSync(): Long

    /** Whether [sync] has been signalled, asked without waiting for it. */
    fun signalled(sync: Long): Boolean

    fun deleteSync(sync: Long)

    fun flush()
}

// The values of the GL enums the passes use, the same in OpenGL ES 3.0 and WebGL 2.
const val GL_TRIANGLES = 0x0004
const val GL_TRIANGLE_STRIP = 0x0005
const val GL_DEPTH_TEST = 0x0B71
const val GL_BLEND = 0x0BE2
const val GL_SCISSOR_TEST = 0x0C11
const val GL_UNPACK_ALIGNMENT = 0x0CF5
const val GL_TEXTURE_2D = 0x0DE1
const val GL_UNSIGNED_BYTE = 0x1401
const val GL_FLOAT = 0x1406
const val GL_RED = 0x1903
const val GL_RGBA = 0x1908
const val GL_NEAREST = 0x2600
const val GL_LINEAR = 0x2601
const val GL_TEXTURE_MAG_FILTER = 0x2800
const val GL_TEXTURE_MIN_FILTER = 0x2801
const val GL_TEXTURE_WRAP_S = 0x2802
const val GL_TEXTURE_WRAP_T = 0x2803
const val GL_RGBA8 = 0x8058
const val GL_CLAMP_TO_EDGE = 0x812F
const val GL_R8 = 0x8229
const val GL_R32F = 0x822E
const val GL_TEXTURE0 = 0x84C0
const val GL_STREAM_READ = 0x88E1
const val GL_PIXEL_PACK_BUFFER = 0x88EB
const val GL_FRAGMENT_SHADER = 0x8B30
const val GL_VERTEX_SHADER = 0x8B31
const val GL_FRAMEBUFFER_COMPLETE = 0x8CD5
const val GL_COLOR_ATTACHMENT0 = 0x8CE0
const val GL_FRAMEBUFFER = 0x8D40
