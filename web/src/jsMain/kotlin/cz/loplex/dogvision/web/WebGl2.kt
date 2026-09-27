package cz.loplex.dogvision.web

import org.khronos.webgl.ArrayBufferView
import org.khronos.webgl.WebGLObject
import org.khronos.webgl.WebGLRenderingContext

/**
 * The part of WebGL 2 the passes use beyond WebGL 1, which is all Kotlin's standard library declares: vertex array
 * objects, fences, reading pixels into a buffer on the GPU and that buffer back, and copying between framebuffers.
 */
abstract external class WebGL2RenderingContext : WebGLRenderingContext {
    fun createVertexArray(): WebGLVertexArrayObject?

    fun bindVertexArray(array: WebGLVertexArrayObject?)

    fun deleteVertexArray(array: WebGLVertexArrayObject?)

    fun fenceSync(condition: Int, flags: Int): WebGLSync?

    fun clientWaitSync(sync: WebGLSync?, flags: Int, timeout: Int): Int

    fun deleteSync(sync: WebGLSync?)

    /** Reads pixels into the pixel pack buffer bound, from [offset] bytes into it. */
    fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, offset: Int)

    fun getBufferSubData(target: Int, srcByteOffset: Int, dstBuffer: ArrayBufferView)

    fun blitFramebuffer(
        srcX0: Int,
        srcY0: Int,
        srcX1: Int,
        srcY1: Int,
        dstX0: Int,
        dstY0: Int,
        dstX1: Int,
        dstY1: Int,
        mask: Int,
        filter: Int,
    )
}

external class WebGLVertexArrayObject : WebGLObject

external class WebGLSync : WebGLObject

/** WebGL 2's values of the GL enums of the same names. */
internal object Gl2 {
    const val SYNC_GPU_COMMANDS_COMPLETE = 0x9117
    const val ALREADY_SIGNALED = 0x911A
    const val CONDITION_SATISFIED = 0x911C
    const val READ_FRAMEBUFFER = 0x8CA8
    const val DRAW_FRAMEBUFFER = 0x8CA9
}
