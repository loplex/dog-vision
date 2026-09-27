package cz.loplex.dogvision.desktop

/**
 * The two APIs the window draws through, each in the context this system makes for it: on Windows, ANGLE's OpenGL ES
 * and WGL's desktop OpenGL, as the window takes them; elsewhere, EGL's of both on the GPU, where desktop OpenGL stands
 * in for WGL's.
 */
enum class TestApi(val title: String, val open: () -> GlContext) {
    ES("OpenGL ES 3.0", { if (onWindows) EglContext.angle() else EglContext.onDevice(EglContext.Api.ES) }),
    DESKTOP("OpenGL 3.3 core", { if (onWindows) WglContext() else EglContext.onDevice(EglContext.Api.DESKTOP) }),
}
