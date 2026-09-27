package cz.loplex.dogvision.desktop

import org.lwjgl.egl.EGL10.EGL_NONE
import org.lwjgl.egl.EGL10.EGL_NO_CONTEXT
import org.lwjgl.egl.EGL10.EGL_NO_DISPLAY
import org.lwjgl.egl.EGL10.EGL_NO_SURFACE
import org.lwjgl.egl.EGL10.EGL_SURFACE_TYPE
import org.lwjgl.egl.EGL10.eglChooseConfig
import org.lwjgl.egl.EGL10.eglCreateContext
import org.lwjgl.egl.EGL10.eglDestroyContext
import org.lwjgl.egl.EGL10.eglGetDisplay
import org.lwjgl.egl.EGL10.eglGetError
import org.lwjgl.egl.EGL10.eglInitialize
import org.lwjgl.egl.EGL10.eglMakeCurrent
import org.lwjgl.egl.EGL10.eglTerminate
import org.lwjgl.egl.EGL12.EGL_OPENGL_ES_API
import org.lwjgl.egl.EGL12.EGL_RENDERABLE_TYPE
import org.lwjgl.egl.EGL12.eglBindAPI
import org.lwjgl.egl.EGL14.EGL_DEFAULT_DISPLAY
import org.lwjgl.egl.EGL14.EGL_OPENGL_API
import org.lwjgl.egl.EGL14.EGL_OPENGL_BIT
import org.lwjgl.egl.EGL15.EGL_CONTEXT_MAJOR_VERSION
import org.lwjgl.egl.EGL15.EGL_CONTEXT_MINOR_VERSION
import org.lwjgl.egl.EGL15.EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT
import org.lwjgl.egl.EGL15.EGL_CONTEXT_OPENGL_PROFILE_MASK
import org.lwjgl.egl.EGL15.EGL_OPENGL_ES3_BIT
import org.lwjgl.egl.EXTDeviceDRMRenderNode.EGL_DRM_RENDER_NODE_FILE_EXT
import org.lwjgl.egl.EXTDeviceEnumeration.eglQueryDevicesEXT
import org.lwjgl.egl.EXTDeviceQuery.eglQueryDeviceStringEXT
import org.lwjgl.egl.EXTPlatformBase.eglGetPlatformDisplayEXT
import org.lwjgl.egl.EXTPlatformDevice.EGL_PLATFORM_DEVICE_EXT
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C
import org.lwjgl.opengles.GLES
import org.lwjgl.opengles.GLES20
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack
import java.io.IOException
import java.nio.IntBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * An OpenGL ES 3.0 context through EGL on the [display] given, with no surface, current on the thread that makes it;
 * or, for the tests of [LwjglGl] on Linux, a desktop OpenGL 3.3 core context, which Windows's WGL gives.
 *
 * LWJGL's library of the API is loaded for the context and unloaded with it, as [loadApi] says why.
 *
 * Throws IllegalStateException where the display, EGL or the context asked for is missing, having undone what it did.
 */
class EglContext private constructor(private val display: Long, private val api: Api = Api.ES) : GlContext {
    /** An API EGL makes a context for: what to bind and ask for, and how LWJGL reads its strings. */
    enum class Api(
        val title: String,
        val eglApi: Int,
        val renderable: Int,
        val contextAttributes: IntArray,
        val getString: (Int) -> String?,
    ) {
        ES(
            "OpenGL ES 3.0",
            EGL_OPENGL_ES_API,
            EGL_OPENGL_ES3_BIT,
            intArrayOf(EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0),
            GLES20::glGetString,
        ),
        DESKTOP(
            "OpenGL 3.3 core",
            EGL_OPENGL_API,
            EGL_OPENGL_BIT,
            intArrayOf(
                EGL_CONTEXT_MAJOR_VERSION,
                3,
                EGL_CONTEXT_MINOR_VERSION,
                3,
                EGL_CONTEXT_OPENGL_PROFILE_MASK,
                EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
            ),
            GL33C::glGetString,
        ),
    }

    private var context = EGL_NO_CONTEXT
    private var initialized = false
    private var loaded = false

    override val renderer: String
    override val version: String

    init {
        try {
            check(display != EGL_NO_DISPLAY) { "EGL cannot open the display (error 0x${eglError()})" }
            MemoryStack.stackPush().use { stack ->
                val major = stack.mallocInt(1)
                val minor = stack.mallocInt(1)
                check(eglInitialize(display, major, minor)) { "Cannot initialise EGL (error 0x${eglError()})" }
                initialized = true
                check(eglBindAPI(api.eglApi)) { "EGL has no ${api.title} (error 0x${eglError()})" }
                // No surface of any kind is asked for, as none is drawn into.
                val attributes = stack.ints(EGL_RENDERABLE_TYPE, api.renderable, EGL_SURFACE_TYPE, 0, EGL_NONE)
                val configs = stack.mallocPointer(1)
                val count = stack.mallocInt(1)
                check(eglChooseConfig(display, attributes, configs, count) && count[0] > 0) {
                    "EGL has no configuration for ${api.title} (error 0x${eglError()})"
                }
                val version = stack.ints(*api.contextAttributes, EGL_NONE)
                context = eglCreateContext(display, configs[0], EGL_NO_CONTEXT, version)
                check(context != EGL_NO_CONTEXT) { "Cannot make an ${api.title} context (error 0x${eglError()})" }
            }
            check(eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context)) {
                "Cannot make the ${api.title} context current (error 0x${eglError()})"
            }
            when (api) {
                Api.ES -> {
                    loadApi(GLES::create)
                    loaded = true
                    GLES.createCapabilities()
                }

                Api.DESKTOP -> {
                    // Desktop GL's functions come through EGL here, not through GLX, LWJGL's default on Linux.
                    Configuration.OPENGL_CONTEXT_API.set("EGL")
                    loadApi(GL::create)
                    loaded = true
                    GL.createCapabilities()
                }
            }
            renderer = api.getString(GLES20.GL_RENDERER).orEmpty()
            version = api.getString(GLES20.GL_VERSION).orEmpty()
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    override val gl: DesktopGl = when (api) {
        Api.ES -> LwjglGles()
        Api.DESKTOP -> LwjglGl()
    }

    /**
     * Whether GL renders in software, on the CPU, as Mesa's llvmpipe and softpipe do, and Direct3D's WARP, which ANGLE
     * names as Microsoft's Basic Render Driver.
     */
    override val software: Boolean
        get() = listOf("llvmpipe", "softpipe", "Microsoft Basic Render Driver").any { it in renderer }

    override fun close() = release()

    /** Undoes as much of making the context as was done, down to loading LWJGL's library of its API. */
    private fun release() {
        if (!initialized) return
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context)
        eglTerminate(display)
        if (loaded) {
            when (api) {
                Api.ES -> {
                    GLES.setCapabilities(null)
                    GLES.destroy()
                }

                Api.DESKTOP -> {
                    GL.setCapabilities(null)
                    GL.destroy()
                }
            }
        }
        initialized = false
    }

    companion object {
        private const val MAX_DEVICES = 16

        private fun eglError() = eglGetError().toString(16)

        /**
         * A context on a GPU that EGL's device platform names, as on Linux.
         *
         * The device is chosen, the first with a DRM render node, which a GPU has and Mesa's software renderer has
         * not; the display EGL gives by default can be the software renderer's, as its GBM platform's is on a machine
         * with a Radeon 680M. Mesa's surfaceless platform would pick the GPU as well, but LWJGL 3.4.3 cannot ask for
         * it: the platform takes a null native display, which LWJGL's binding refuses. Where no device has a render
         * node, the context is the first device's, and [software] says so.
         */
        fun onDevice(api: Api = Api.ES): EglContext =
            EglContext(eglGetPlatformDisplayEXT(EGL_PLATFORM_DEVICE_EXT, device(), null as IntBuffer?), api)

        /**
         * A context through ANGLE, which runs OpenGL ES over Direct3D 11, as on Windows, where no system EGL gives ES.
         *
         * ANGLE's `libEGL.dll` and `libGLESv2.dll` come in the JAR, from Nucleus's build of ANGLE, and are written
         * into a folder named after their contents under the system's temporary folder, as LWJGL writes its own
         * natives, and LWJGL is told to load them from there; libEGL finds libGLESv2 beside itself. The display is
         * EGL's default, which is ANGLE's Direct3D 11 one: LWJGL 3.4.3's `eglGetPlatformDisplayEXT` would refuse the
         * default's null native display, as it refuses Mesa's surfaceless platform's. Throws IllegalStateException
         * where ANGLE is not in the JAR or cannot be written out.
         */
        fun angle(): EglContext {
            val folder = angleLibraries()
            Configuration.EGL_LIBRARY_NAME.set(folder.resolve("libEGL.dll").toString())
            Configuration.OPENGLES_LIBRARY_NAME.set(folder.resolve("libGLESv2.dll").toString())
            return EglContext(eglGetDisplay(EGL_DEFAULT_DISPLAY))
        }

        /** Where ANGLE's DLLs for this processor are in Nucleus's JAR. */
        private val ANGLE_RESOURCES = mapOf("amd64" to "nucleus/native/win32-x64")

        private val ANGLE_LIBRARIES = listOf("libEGL.dll", "libGLESv2.dll")

        /** The folder that ANGLE's DLLs are written into, once for each build of them, and are loaded from. */
        private fun angleLibraries(): Path {
            val arch = System.getProperty("os.arch")
            val resources = checkNotNull(ANGLE_RESOURCES[arch]) { "ANGLE is not shipped for the processor $arch" }
            val libraries = ANGLE_LIBRARIES.associateWith { name ->
                val stream = EglContext::class.java.classLoader.getResourceAsStream("$resources/$name")
                checkNotNull(stream) { "ANGLE's $name is not in the JAR" }.use { it.readBytes() }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            libraries.values.forEach(digest::update)
            val hash = digest.digest().take(ANGLE_HASH_BYTES).joinToString("") { "%02x".format(it) }
            val user = System.getProperty("user.name").filter(Char::isLetterOrDigit)
            val folder = Path.of(System.getProperty("java.io.tmpdir"), "dog-vision-$user", "angle-$hash")
            try {
                Files.createDirectories(folder)
                for ((name, bytes) in libraries) {
                    val file = folder.resolve(name)
                    // A file already there is the same build, and may be loaded by another window, so is kept.
                    if (Files.exists(file) && Files.size(file) == bytes.size.toLong()) continue
                    val written = Files.createTempFile(folder, name, ".part")
                    Files.write(written, bytes)
                    Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }
            } catch (error: IOException) {
                throw IllegalStateException("Cannot write ANGLE's libraries into $folder: ${error.message}", error)
            }
            return folder
        }

        private const val ANGLE_HASH_BYTES = 6

        /** The first EGL device with a DRM render node, else the first of all. */
        private fun device(): Long = MemoryStack.stackPush().use { stack ->
            val devices = stack.mallocPointer(MAX_DEVICES)
            val count = stack.mallocInt(1)
            check(eglQueryDevicesEXT(devices, count) && count[0] > 0) { "EGL finds no device (error 0x${eglError()})" }
            val all = List(count[0]) { devices[it] }
            all.firstOrNull { eglQueryDeviceStringEXT(it, EGL_DRM_RENDER_NODE_FILE_EXT) != null } ?: all.first()
        }
    }
}
