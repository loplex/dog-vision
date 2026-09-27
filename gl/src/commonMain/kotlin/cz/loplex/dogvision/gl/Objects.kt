package cz.loplex.dogvision.gl

/** A linked GL program, with its uniforms looked up by name once. */
class Program(private val gl: Gl, vertex: String, fragment: String) {
    val id: Int = gl.createProgram()
    private val locations = mutableMapOf<String, Int>()

    init {
        val shaders = listOf(compile(GL_VERTEX_SHADER, vertex), compile(GL_FRAGMENT_SHADER, fragment))
        shaders.forEach { gl.attachShader(id, it) }
        gl.linkProgram(id)
        shaders.forEach(gl::deleteShader)
        check(gl.programLinked(id)) { "Cannot link a program: ${gl.programInfoLog(id)}" }
    }

    fun use(): Program = apply { gl.useProgram(id) }

    fun release() = gl.deleteProgram(id)

    fun uniform(name: String): Int = locations.getOrPut(name) { gl.uniformLocation(id, name) }

    /** Binds [texture] to texture unit [unit] and points the sampler uniform [name] at it. */
    fun sampler(name: String, unit: Int, texture: Int) {
        gl.activeTexture(GL_TEXTURE0 + unit)
        gl.bindTexture(GL_TEXTURE_2D, texture)
        gl.uniform1i(uniform(name), unit)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = gl.createShader(type)
        gl.shaderSource(shader, source)
        gl.compileShader(shader)
        check(gl.shaderCompiled(shader)) { "Cannot compile a shader: ${gl.shaderInfoLog(shader)}" }
        return shader
    }
}

/** A new texture, sampled at whole pixels unless [filtered], and bound. */
fun texture(gl: Gl, filtered: Boolean = false): Int {
    val id = gl.createTexture()
    gl.bindTexture(GL_TEXTURE_2D, id)
    val filter = if (filtered) GL_LINEAR else GL_NEAREST
    gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
    gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
    gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    return id
}

/** An RGBA8 texture with a framebuffer to render into it, reallocated when its size changes. */
class Target(private val gl: Gl, private val filtered: Boolean = false) {
    var texture = 0
        private set
    var framebuffer = 0
        private set
    var width = 0
        private set
    var height = 0
        private set

    fun ensure(width: Int, height: Int) {
        if (width == this.width && height == this.height && texture != 0) return
        release()
        texture = texture(gl, filtered)
        gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, GL_RGBA, GL_UNSIGNED_BYTE, null)
        framebuffer = gl.createFramebuffer()
        gl.bindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        gl.framebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        check(gl.checkFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
            "Cannot render into a $width x $height texture"
        }
        this.width = width
        this.height = height
    }

    fun release() {
        if (texture != 0) gl.deleteTexture(texture)
        if (framebuffer != 0) gl.deleteFramebuffer(framebuffer)
        texture = 0
        framebuffer = 0
        width = 0
        height = 0
    }

    /** Forgets the GL objects without deleting them, as when their context is gone. */
    fun lose() {
        texture = 0
        framebuffer = 0
        width = 0
        height = 0
    }
}
