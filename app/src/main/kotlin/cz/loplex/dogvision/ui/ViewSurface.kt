package cz.loplex.dogvision.ui

import android.opengl.EGLExt
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.render.Capture
import cz.loplex.dogvision.render.Drawn
import cz.loplex.dogvision.render.FrameExchange
import cz.loplex.dogvision.render.ViewRenderer
import cz.loplex.dogvision.video.RECORDING_FPS
import cz.loplex.dogvision.video.Recorder
import kotlinx.coroutines.delay
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * The images of [view], drawn by the GPU from the newest of [frames], with room for a caption
 * [captionHeight] pixels tall under each and [gap] pixels between them. [bindCapture] is given a
 * function that asks for the images drawn next while the surface is shown, and null after. While
 * [recorder] is given, every frame is drawn into it too, and the view is drawn at least
 * [REDRAWS_PER_SECOND] times a second, so that a still photo fills every frame of the recording.
 */
@Composable
fun ViewSurface(
    frames: FrameExchange,
    view: View,
    captionHeight: Int,
    gap: Int,
    onDrawn: (Drawn) -> Unit,
    bindCapture: (Capture?) -> Unit,
    recorder: Recorder?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val renderer = remember(frames) { ViewRenderer(frames, onDrawn) }
    val surface = remember(renderer) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(RecordableConfigChooser)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            renderer.requestRender = ::requestRender
        }
    }
    // A surface made anew takes the frames over before the one it replaces is released, which then leaves them alone.
    val onPublish: () -> Unit = remember(surface) { surface::requestRender }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    AndroidView(
        modifier = modifier,
        factory = {
            frames.onPublish = onPublish
            surface
        },
        update = {
            renderer.view = view
            renderer.recorder = recorder
            renderer.captionHeight = captionHeight
            renderer.gap = gap
            it.requestRender()
        },
        onRelease = { if (frames.onPublish === onPublish) frames.onPublish = null },
    )
    LaunchedEffect(recorder) {
        while (recorder != null) {
            surface.requestRender()
            delay(1000L / REDRAWS_PER_SECOND)
        }
    }
    DisposableEffect(renderer) {
        bindCapture { onImages ->
            renderer.capture(onImages)
            surface.requestRender()
        }
        onDispose { bindCapture(null) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surface.onResume()
                Lifecycle.Event.ON_PAUSE -> surface.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** How often the view is drawn while recording: twice the recording's rate, so that no frame of it is missed. */
private const val REDRAWS_PER_SECOND = 2 * RECORDING_FPS

/**
 * Chooses an 8-bit RGB configuration for OpenGL ES 3.0 that a video encoder's input surface can be
 * made with too, and one it cannot where the device has none.
 */
private object RecordableConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        for (recordable in listOf(true, false)) {
            val attributes = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                *(if (recordable) intArrayOf(EGLExt.EGL_RECORDABLE_ANDROID, 1) else intArrayOf()),
                EGL10.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val found = IntArray(1)
            if (egl.eglChooseConfig(display, attributes, configs, 1, found) && found[0] > 0) return configs[0]!!
        }
        error("No EGL configuration for OpenGL ES 3.0")
    }
}
