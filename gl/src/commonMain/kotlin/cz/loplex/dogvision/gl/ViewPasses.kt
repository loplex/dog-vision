package cz.loplex.dogvision.gl

import cz.loplex.dogvision.core.Box
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Matrix
import cz.loplex.dogvision.core.Srgb
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.gaussianKernel
import cz.loplex.dogvision.core.rgb
import cz.loplex.dogvision.core.simulationOf
import kotlin.math.exp

/**
 * The GL passes that render a view's images from a frame, through [gl], in whichever context draws them: the Android
 * app's screen, a video being converted, or the web page's canvas. Everything here runs on that context's thread,
 * between [create] and [release].
 *
 * The frame is turned upright into a texture of its own, each image of the view is rendered into a texture of its own,
 * the size the upright frame has, and [draw] puts the images into boxes of the framebuffer bound. Rendering the map of
 * differences into a texture of its own keeps it from reading the texture it draws into, which GL leaves undefined.
 *
 * The passes bind a vertex array of their own while they draw and unbind it after, so that a context they share with
 * code drawing from client-side vertex arrays, as Media3's does, is left as it was.
 */
class ViewPasses(private val gl: Gl) {
    private lateinit var upright: Program
    private lateinit var copy: Program
    private lateinit var colour: Program
    private lateinit var blurAcross: Program
    private lateinit var blurDown: Program
    private lateinit var difference: Program
    private lateinit var count: Program
    private lateinit var everyEighth: Program
    private lateinit var screen: Program
    private var decodeTable = 0
    private var encodeTable = 0
    private var vertexArray = 0

    /**
     * The count of the map's noticeable pixels under way: the buffer the GPU reads it back into, the fence it signals
     * when it has, and how many blocks and pixels the map had. [countWanted] asks for a count of the map composed last,
     * started once none is under way.
     */
    private var countBuffer = 0
    private var countFence = 0L
    private var countedBlocks = 0
    private var countedPixels = 0L
    private var countWanted = false

    /** The frame, upright. */
    val frame = Target(gl)
    private val across = Target(gl)

    /** The view's images, left to right or top to bottom; drawn scaled, so filtered. */
    private val images = List(3) { Target(gl, filtered = true) }
    private val counts = Target(gl)
    private val samples = Target(gl)

    /** Compiles the programs and makes the lookup tables, in the context current on this thread. */
    fun create() {
        upright = Program(gl, Shaders.FULL_VIEWPORT, Shaders.UPRIGHT)
        copy = Program(gl, Shaders.FULL_VIEWPORT, Shaders.COPY)
        colour = Program(gl, Shaders.FULL_VIEWPORT, Shaders.COLOUR)
        blurAcross = Program(gl, Shaders.FULL_VIEWPORT, Shaders.BLUR_ACROSS)
        blurDown = Program(gl, Shaders.FULL_VIEWPORT, Shaders.BLUR_DOWN_AND_COLOUR)
        difference = Program(gl, Shaders.FULL_VIEWPORT, Shaders.DIFFERENCE)
        count = Program(gl, Shaders.FULL_VIEWPORT, Shaders.COUNT)
        everyEighth = Program(gl, Shaders.FULL_VIEWPORT, Shaders.EVERY_EIGHTH)
        screen = Program(gl, Shaders.SCREEN_VERTEX, Shaders.SCREEN)
        gl.pixelStorei(GL_UNPACK_ALIGNMENT, 1)
        decodeTable = texture(gl)
        gl.texImage2D(GL_TEXTURE_2D, 0, GL_R32F, 256, 1, GL_RED, GL_FLOAT, Srgb.decode)
        encodeTable = texture(gl)
        val encode = ByteArray(Srgb.ENCODE_STEPS) { Srgb.encode(it / (Srgb.ENCODE_STEPS - 1f)).toByte() }
        gl.texImage2D(GL_TEXTURE_2D, 0, GL_R8, 64, 64, GL_RED, GL_UNSIGNED_BYTE, encode)
        vertexArray = gl.createVertexArray()
        countBuffer = gl.createBuffer()
    }

    private val targets: List<Target> get() = listOf(frame, across, counts, samples) + images

    /** Forgets the GL objects without deleting them, as when their context is gone. */
    fun lose() {
        targets.forEach(Target::lose)
        countFence = 0L
        countWanted = false
    }

    /** Deletes the GL objects, in the context they were made in. */
    fun release() {
        targets.forEach(Target::release)
        listOf(upright, copy, colour, blurAcross, blurDown, difference, count, everyEighth, screen)
            .forEach(Program::release)
        gl.deleteTexture(decodeTable)
        gl.deleteTexture(encodeTable)
        gl.deleteVertexArray(vertexArray)
        gl.deleteBuffer(countBuffer)
        if (countFence != 0L) gl.deleteSync(countFence)
        countFence = 0L
    }

    /**
     * Turns the frame in [texture], [width] x [height], upright into [frame]: [rotation] degrees clockwise, then
     * mirrored if [mirrored] says so.
     */
    fun turnUpright(texture: Int, width: Int, height: Int, rotation: Int, mirrored: Boolean) = drawing {
        if (rotation % 180 == 0) frame.ensure(width, height) else frame.ensure(height, width)
        into(frame)
        upright.use()
        common(upright)
        upright.sampler("uRaw", 2, texture)
        gl.uniform1i(upright.uniform("uRotation"), rotation)
        gl.uniform1i(upright.uniform("uMirrored"), if (mirrored) 1 else 0)
        gl.uniform2i(upright.uniform("uSize"), frame.width, frame.height)
        gl.drawArrays(GL_TRIANGLES, 0, 3)
    }

    /** Renders every image of [view] of the upright frame; [countDifferences] counts what its map marks. */
    fun compose(view: View) = drawing {
        val width = frame.width
        val height = frame.height
        val mean by lazy(::meanLinearRgb)
        val right = images[if (view.sideBySide) 1 else 0]
        val (rightMatrix, rightBlur) = simulationOf(width, view.params) { mean }
        simulate(rightMatrix, rightBlur, right)
        if (!view.sideBySide) return@drawing
        val left = images[0]
        val compare = view.compare
        if (compare == null) {
            left.ensure(width, height)
            into(left)
            copy.use()
            common(copy)
            copy.sampler("uSource", 2, frame.texture)
            gl.drawArrays(GL_TRIANGLES, 0, 3)
        } else {
            val (leftMatrix, leftBlur) = simulationOf(width, view.params.copy(species = compare)) { mean }
            simulate(leftMatrix, leftBlur, left)
        }
        if (!view.difference) return@drawing
        val map = images[2]
        map.ensure(width, height)
        into(map)
        difference.use()
        common(difference)
        difference.sampler("uLeftImage", 2, left.texture)
        difference.sampler("uRightImage", 3, right.texture)
        gl.drawArrays(GL_TRIANGLES, 0, 3)
    }

    /**
     * Draws the first images composed into [boxes], one each, of the framebuffer bound, [width] x [height], measured
     * from its top left corner as the top of an image is seen.
     */
    fun draw(boxes: List<Box>, width: Int, height: Int) = drawing {
        gl.viewport(0, 0, width, height)
        screen.use()
        boxes.forEachIndexed { i, box ->
            screen.sampler("uSource", 0, images[i].texture)
            gl.uniform4f(
                screen.uniform("uRect"),
                2f * box.left / width - 1,
                1 - 2f * box.bottom / height,
                2f * box.right / width - 1,
                1 - 2f * box.top / height,
            )
            gl.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    /**
     * The first [count] images composed, read back from the GPU, left to right or top to bottom. The map's alpha, which
     * marked what COUNT counts, is made opaque.
     */
    fun readImages(count: Int): List<Image> = images.take(count).map { target ->
        gl.bindFramebuffer(GL_FRAMEBUFFER, target.framebuffer)
        val pixels = read(target)
        Image(
            target.width,
            target.height,
            IntArray(target.width * target.height) {
                val offset = it * 4
                rgb(
                    pixels[offset].toInt() and 0xFF,
                    pixels[offset + 1].toInt() and 0xFF,
                    pixels[offset + 2].toInt() and 0xFF,
                )
            },
        )
    }

    /** Runs [passes] with the passes' own vertex array bound, and nothing blending or cutting what they draw. */
    private inline fun <T> drawing(passes: () -> T): T {
        gl.bindVertexArray(vertexArray)
        gl.disable(GL_BLEND)
        gl.disable(GL_DEPTH_TEST)
        gl.disable(GL_SCISSOR_TEST)
        try {
            return passes()
        } finally {
            gl.bindVertexArray(0)
        }
    }

    /** Renders one simulation of the upright frame into [target]. */
    private fun simulate(matrix: Matrix, blur: Pair<Double, Double>?, target: Target) {
        val width = frame.width
        val height = frame.height
        target.ensure(width, height)
        if (blur == null) {
            into(target)
            colour.use()
            common(colour)
            colour.sampler("uSource", 2, frame.texture)
            matrix(colour, matrix)
            gl.drawArrays(GL_TRIANGLES, 0, 3)
            return
        }
        across.ensure(width, height)
        into(across)
        blurAcross.use()
        common(blurAcross)
        blurAcross.sampler("uSource", 2, frame.texture)
        kernel(blurAcross, blur.first, width)
        gl.drawArrays(GL_TRIANGLES, 0, 3)
        into(target)
        blurDown.use()
        common(blurDown)
        blurDown.sampler("uSource", 2, across.texture)
        kernel(blurDown, blur.second, height)
        matrix(blurDown, matrix)
        gl.drawArrays(GL_TRIANGLES, 0, 3)
    }

    /**
     * The upright frame's mean linear RGB, from every 8th pixel of every 8th row as core's meanLinearRgb takes it, so
     * that the two agree exactly.
     */
    private fun meanLinearRgb(): DoubleArray {
        val across = (frame.width + 7) / 8
        val down = (frame.height + 7) / 8
        samples.ensure(across, down)
        into(samples)
        everyEighth.use()
        everyEighth.sampler("uSource", 2, frame.texture)
        gl.drawArrays(GL_TRIANGLES, 0, 3)
        val pixels = read(samples)
        val sum = DoubleArray(3)
        for (i in 0 until across * down) {
            for (channel in 0 until 3) sum[channel] += Srgb.decode[pixels[i * 4 + channel].toInt() and 0xFF]
        }
        return DoubleArray(3) { sum[it] / (across * down) }
    }

    /**
     * The share of pixels that the map of differences composed last marks, counted at once: for a still photo, which
     * is composed once and can wait for the GPU that one time. The map must have been composed.
     */
    fun differenceShare(): Double = drawing {
        val map = images[2]
        countInto(map)
        val pixels = read(counts)
        var noticeable = 0L
        for (i in 0 until counts.width * counts.height) noticeable += pixels[i * 4].toInt() and 0xFF
        noticeable.toDouble() / (map.width.toLong() * map.height)
    }

    /**
     * Asks for the share of pixels that the map of differences composed last marks, which [takeDifferenceShare] hands
     * over once the GPU has counted it. The map must have been composed.
     */
    fun countDifferences() {
        countWanted = true
    }

    /** Whether a count asked for has not been handed over yet. */
    val counting: Boolean get() = countWanted || countFence != 0L

    /**
     * The share of pixels that differ noticeably, once the GPU has counted it, and null until then; it never waits for
     * the GPU. Call it once a frame while [counting]: it starts the count asked for when none is under way, and a count
     * asked for meanwhile follows it.
     */
    fun takeDifferenceShare(): Double? {
        var share: Double? = null
        if (countFence != 0L) {
            if (!gl.signalled(countFence)) return null
            gl.deleteSync(countFence)
            countFence = 0L
            gl.bindBuffer(GL_PIXEL_PACK_BUFFER, countBuffer)
            val pixels = gl.bufferSubData(GL_PIXEL_PACK_BUFFER, countedBlocks * 4)
            gl.bindBuffer(GL_PIXEL_PACK_BUFFER, 0)
            var noticeable = 0L
            for (i in 0 until countedBlocks) noticeable += pixels[i * 4].toInt() and 0xFF
            share = noticeable.toDouble() / countedPixels
        }
        if (countWanted) startCount()
        return share
    }

    /** Counts the map's noticeable pixels in blocks of 8 x 8 and reads the counts back into [countBuffer]. */
    private fun startCount() = drawing {
        countWanted = false
        val map = images[2]
        countInto(map)
        gl.bindBuffer(GL_PIXEL_PACK_BUFFER, countBuffer)
        gl.bufferData(GL_PIXEL_PACK_BUFFER, counts.width * counts.height * 4, GL_STREAM_READ)
        gl.readPixelsIntoPackBuffer(0, 0, counts.width, counts.height)
        gl.bindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        countFence = gl.fenceSync()
        gl.flush() // so that the fence reaches the GPU, and is signalled, without a buffer swap
        countedBlocks = counts.width * counts.height
        countedPixels = map.width.toLong() * map.height
    }

    /** Counts the noticeable pixels of [map] in blocks of 8 x 8 into [counts], which is left bound. */
    private fun countInto(map: Target) {
        counts.ensure((map.width + 7) / 8, (map.height + 7) / 8)
        into(counts)
        count.use()
        common(count)
        count.sampler("uSource", 2, map.texture)
        gl.drawArrays(GL_TRIANGLES, 0, 3)
    }

    /** The pixels of [target], which must be the framebuffer bound. */
    private fun read(target: Target): ByteArray {
        val pixels = ByteArray(target.width * target.height * 4)
        gl.readPixels(0, 0, target.width, target.height, pixels)
        return pixels
    }

    private fun into(target: Target) {
        gl.bindFramebuffer(GL_FRAMEBUFFER, target.framebuffer)
        gl.viewport(0, 0, target.width, target.height)
    }

    /** The uniforms every offscreen pass has: the sRGB tables. */
    private fun common(program: Program) {
        program.sampler("uDecode", 0, decodeTable)
        program.sampler("uEncode", 1, encodeTable)
    }

    private fun matrix(program: Program, matrix: Matrix) {
        val values = FloatArray(9) { matrix[it / 3, it % 3].toFloat() }
        gl.uniformMatrix3fv(program.uniform("uMatrix"), true, values)
    }

    private fun kernel(program: Program, sigma: Double, length: Int) {
        val radius = gaussianKernel(sigma).size / 2
        val sum = (-radius..radius).sumOf { exp(-it.toDouble() * it / (2 * sigma * sigma)) }
        gl.uniform1f(program.uniform("uSigma"), sigma.toFloat())
        gl.uniform1i(program.uniform("uRadius"), radius)
        gl.uniform1f(program.uniform("uNorm"), (1 / sum).toFloat())
        gl.uniform1i(program.uniform("uLength"), length)
    }
}
