package cz.loplex.dogvision.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import cz.loplex.dogvision.render.FrameExchange
import java.util.concurrent.Executors

/**
 * The camera's frames, as RGBA, handed to [frames] while [owner] is started.
 *
 * 1280 x 720 is asked for: enough to show acuity blur for most species. A camera without it gives
 * the size closest to it, but none longer than 2048, the texture size every GPU of OpenGL ES 3.0 has:
 * the renderer keeps the frame, and each image rendered from it, in a texture of that size.
 */
class CameraFeed(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val frames: FrameExchange,
    private val onError: (Throwable) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    /** The display's rotation, kept for a camera that has not started yet when it turns. */
    private var rotation = 0

    /**
     * Starts the back or the front camera, stopping the other. The frames of the one stopped that
     * come in meanwhile belong to a generation before, and are dropped.
     */
    fun start(front: Boolean, rotation: Int) {
        this.rotation = rotation
        val generation = frames.open()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get().also { provider = it }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .setResolutionFilter { sizes, _ ->
                                sizes.filter { maxOf(it.width, it.height) <= ES3_TEXTURE_SIZE }
                            }
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetRotation(this.rotation)
                    .build()
                analysis.setAnalyzer(executor) { deliver(it, front, generation) }
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, analysis)
                this.analysis = analysis
            } catch (error: Exception) {
                onError(error)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Follows the display as it turns, so that each frame's rotation brings it upright on it. */
    fun setRotation(rotation: Int) {
        this.rotation = rotation
        analysis?.targetRotation = rotation
    }

    fun stop() {
        provider?.unbindAll()
        analysis = null
    }

    fun release() {
        stop()
        executor.shutdown()
    }

    private fun deliver(image: ImageProxy, front: Boolean, generation: Int) {
        image.use {
            val plane = image.planes[0]
            val frame = frames.obtain(image.width, image.height) ?: return
            val source = plane.buffer
            val rowBytes = image.width * 4
            frame.pixels.clear()
            for (y in 0 until image.height) {
                source.limit(y * plane.rowStride + rowBytes).position(y * plane.rowStride)
                frame.pixels.put(source)
            }
            frame.rotation = image.imageInfo.rotationDegrees
            frame.mirrored = front
            frames.publish(frame, generation)
        }
    }
}

/** The longest side of a texture that OpenGL ES 3.0 lets no GPU refuse. */
private const val ES3_TEXTURE_SIZE = 2048
