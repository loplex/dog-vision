package cz.loplex.dogvision.video

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import cz.loplex.dogvision.core.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/**
 * How a video was written: its format, such as H.265, the encoder, whether it has the original's
 * sound or is [silent] as a recording is, and the size it was scaled down to if the encoder could not
 * take the view's, null if it was not.
 */
data class Written(
    val format: String,
    val encoder: String,
    val sound: Boolean,
    val scaledTo: Pair<Int, Int>?,
    val silent: Boolean = false,
)

/**
 * Writes [view] of every frame of the video at [uri] to [output], at the video's size and rate,
 * with its sound; [onProgress] is told the share done, on the main thread.
 *
 * Media3's Transformer decodes the video, draws each frame through a [ViewEffect], and encodes it as
 * H.265 where the device has an encoder for it and as H.264 where not, on the hardware it picks, at
 * a smaller size if the encoder cannot take the view's. The sound is carried over as it is where an
 * .mp4 holds it, and re-encoded to a format that the encoder and an .mp4 take where not. An HDR
 * video is tone-mapped to SDR first, since the model works on SDR. Cancelling the coroutine stops the
 * conversion, and [output] is then left unfinished.
 *
 * [textureLimit] caps the size of the view's texture below the GPU's own, as a GPU with a smaller one would.
 *
 * Throws IOException if the video cannot be converted.
 */
@OptIn(UnstableApi::class)
suspend fun convertVideo(
    context: Context,
    uri: Uri,
    view: View,
    output: File,
    textureLimit: Int = Int.MAX_VALUE,
    onProgress: (Double) -> Unit,
): Written = withContext(Dispatchers.Main) {
    val result = CompletableDeferred<ExportResult>()
    val transformer = Transformer.Builder(context)
        .setVideoMimeType(MimeTypes.VIDEO_H265)
        // Transformer's own asset loader, but for a decoder refusing the video, which the next one is tried for.
        .setAssetLoaderFactory(
            DefaultAssetLoaderFactory(
                context,
                DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build(),
                Clock.DEFAULT,
                null,
            ),
        )
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                result.complete(exportResult)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exception: ExportException) {
                result.completeExceptionally(IOException(exception.message ?: exception.errorCodeName, exception))
            }
        })
        .build()
    val effect = ViewEffect(view, textureLimit)
    val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
        .setEffects(Effects(emptyList(), listOf(effect)))
        .build()
    val composition = Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(item)))
        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        .build()
    transformer.start(composition, output.path)
    val progress = ProgressHolder()
    val exported = try {
        var done: ExportResult? = null
        while (done == null) {
            done = withTimeoutOrNull(PROGRESS_INTERVAL_MS) { result.await() }
            if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress(progress.progress / 100.0)
            }
        }
        done
    } catch (cancelled: CancellationException) {
        transformer.cancel()
        throw cancelled
    }
    // Compared by their pixels, since Transformer turns a video taller than wide on its side to encode it.
    val rendered = effect.renderedSize
    val scaled = rendered != null && exported.width > 0 &&
        exported.width * exported.height < rendered.first * rendered.second
    Written(
        format = formatName(exported.videoMimeType),
        encoder = exported.videoEncoderName ?: "?",
        sound = exported.audioMimeType != null,
        scaledTo = if (scaled) exported.width to exported.height else null,
    )
}

/** The name a video's format goes by, as the desktop dog-vision names it. */
internal fun formatName(mimeType: String?): String = when (mimeType) {
    MediaFormat.MIMETYPE_VIDEO_HEVC -> "H.265"
    MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
    MediaFormat.MIMETYPE_VIDEO_MPEG4 -> "MPEG-4"
    else -> mimeType ?: "?"
}

private const val PROGRESS_INTERVAL_MS = 250L
