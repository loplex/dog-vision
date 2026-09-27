package cz.loplex.dogvision

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.core.meanLinearRgb
import cz.loplex.dogvision.core.percent
import cz.loplex.dogvision.core.snapshotName
import cz.loplex.dogvision.render.Capture
import cz.loplex.dogvision.render.Drawn
import cz.loplex.dogvision.render.FrameExchange
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.video.Recorder
import cz.loplex.dogvision.video.Written
import cz.loplex.dogvision.video.convertVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/** What is shown: the camera, a photo, or a video. */
sealed interface Source {
    data object Camera : Source

    data class Photo(val uri: Uri, val name: String) : Source

    /** A video, which the screen plays through a VideoFeed of its own while it is shown. */
    data class Video(val uri: Uri, val name: String) : Source
}

/**
 * What the screen shows, kept while the activity is recreated. The controls, the choice of camera and
 * the source are kept in [state] too, so that they come back when Android has ended the app in the
 * background; a photo or a video that cannot be read by then gives way to the camera.
 */
class MainViewModel(application: Application, state: SavedStateHandle) : AndroidViewModel(application) {
    /** The frames of the source shown. */
    val frames = FrameExchange()

    private val _view = MutableStateFlow(state.get<Bundle>(VIEW_STATE)?.toView() ?: View())
    val view: StateFlow<View> = _view.asStateFlow()

    init {
        state.setSavedStateProvider(VIEW_STATE) { _view.value.toBundle() }
    }

    private val _source = MutableStateFlow<Source>(Source.Camera)
    val source: StateFlow<Source> = _source.asStateFlow()

    private val _drawn = MutableStateFlow<Drawn?>(null)

    /** What the renderer drew last: the layout of the images, and the share that differs. */
    val drawn: StateFlow<Drawn?> = _drawn.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** What the last action did, or why it failed, for the user to read. */
    val message: StateFlow<String?> = _message.asStateFlow()

    // The picker's grant to read the file belongs to the activity, which outlives the process Android ends.
    init {
        when (val kept = state.get<Bundle>(SOURCE_STATE)?.toSource()) {
            is Source.Photo -> viewModelScope.launch { openPhoto(kept.uri, kept.name) }
            is Source.Video -> _source.value = kept
            Source.Camera, null -> Unit
        }
        state.setSavedStateProvider(SOURCE_STATE) { _source.value.toBundle() }
    }

    /** Changes the view; while recording, it keeps how many images it has, as a video cannot change its size. */
    fun update(change: (View) -> View) = _view.update { change(it).keepingSizeOf(it) }

    /** Every control back to where it started, but for how many images the view has while recording. */
    fun reset() = update { View() }

    private fun View.keepingSizeOf(old: View): View =
        if (_recorder.value == null) this else copy(sideBySide = old.sideBySide, difference = old.difference)

    /** Called on the GL thread. */
    fun onDrawn(drawn: Drawn) {
        _drawn.value = drawn
    }

    fun onCameraError(error: Throwable) {
        val context = getApplication<Application>()
        _message.value = context.texts.get(Str.CAMERA_FAILED, error.message ?: error.javaClass.simpleName)
    }

    fun say(message: String) {
        _message.value = message
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** Shows the photo or the video at [uri] instead of the camera, as its type says it is. */
    fun openMedia(uri: Uri) {
        if (_recorder.value != null) return
        val context = getApplication<Application>()
        viewModelScope.launch {
            val (type, name) = withContext(Dispatchers.IO) {
                context.contentResolver.getType(uri) to displayName(context, uri)
            }
            if (type?.startsWith("video/") == true) {
                _source.value = Source.Video(uri, name)
                _message.value = null
            } else {
                openPhoto(uri, name)
            }
        }
    }

    /** Called when the video shown cannot be played; the camera is shown instead. */
    fun onVideoError(error: Throwable) {
        val video = source.value as? Source.Video ?: return
        val context = getApplication<Application>()
        val reason = error.message ?: error.javaClass.simpleName
        stopRecording()
        _message.value = context.texts.get(Str.VIDEO_FAILED, video.name, reason)
        openCamera()
    }

    /** Shows the photo at [uri], called [name], instead of the camera, scaled down to [PREVIEW_LONGEST_SIDE]. */
    private suspend fun openPhoto(uri: Uri, name: String) {
        val context = getApplication<Application>()
        val frame = withContext(Dispatchers.IO) {
            val decoded = runCatching { decodePhoto(context, uri, PREVIEW_LONGEST_SIDE) }.getOrNull()
            decoded?.let { (bitmap, turn) -> bitmap.toFrame(turn).also { bitmap.recycle() } }
        }
        if (frame == null) {
            _message.value = context.texts.get(Str.MEDIA_FAILED, name)
            return
        }
        _source.value = Source.Photo(uri, name)
        frames.publish(frame, frames.open())
        _message.value = null
    }

    /** Shows the camera again; not while recording, as the source's size would change the video's. */
    fun openCamera() {
        if (_recorder.value == null) _source.value = Source.Camera
    }

    private val _frontCamera = state.getMutableStateFlow(FRONT_CAMERA_STATE, false)

    /** Whether the camera is the front one, whose image is shown mirrored, as a mirror shows a face. */
    val frontCamera: StateFlow<Boolean> = _frontCamera.asStateFlow()

    /** Switches between the back and the front camera; not while recording, as the frames' size would change. */
    fun switchCamera() {
        if (_recorder.value == null) _frontCamera.update { !it }
    }

    /** Asks the renderer for the images it draws next, with their layout; set while one is shown. */
    var capture: Capture? = null

    /**
     * Saves the view as shown to Pictures, at the size the images are rendered: the camera's
     * resolution, or a photo's scaled-down view.
     */
    fun saveSnapshot() {
        val context = getApplication<Application>()
        val name = snapshotName(view.value, now())
        val capture = capture ?: return
        capture { images, layout ->
            viewModelScope.launch(Dispatchers.IO) {
                _message.value = try {
                    savePng(context, stitch(images, layout.arrangement), name)
                    context.texts.get(Str.SAVED, name, Gallery.IMAGES.folder)
                } catch (error: IOException) {
                    context.texts.get(Str.SNAPSHOT_FAILED, error.message)
                }
            }
        }
    }

    private var conversion: Job? = null

    private val _converting = MutableStateFlow(false)

    /** Whether a photo or a video is being converted at full size. */
    val converting: StateFlow<Boolean> = _converting.asStateFlow()

    /** Runs [convert] as the one conversion there is, which [cancelConversion] stops; the message goes with it. */
    private fun startConversion(dispatcher: CoroutineDispatcher, convert: suspend CoroutineScope.() -> Unit) {
        if (conversion?.isActive == true) return
        _converting.value = true
        conversion = viewModelScope.launch(dispatcher, block = convert).apply {
            invokeOnCompletion { cause ->
                if (cause is CancellationException) _message.value = null
                _converting.value = false
            }
        }
    }

    fun cancelConversion() {
        conversion?.cancel()
    }

    /** The view as a conversion writes it now: its images arranged as the screen shows them. */
    private fun viewToConvert(): View =
        view.value.copy(arrangement = drawn.value?.layout?.arrangement ?: Arrangement.ROW)

    /**
     * Saves the photo shown to Pictures at its full size, as the view shows it now, on the CPU with
     * core's pipeline; what the conversion is at is told as a message.
     */
    fun convertPhoto() {
        val photo = source.value as? Source.Photo ?: return
        val context = getApplication<Application>()
        val facts = context.texts.facts
        val view = viewToConvert()
        val name = snapshotName(view, now(), suffix = "-full")
        startConversion(Dispatchers.Default) {
            _message.value = context.texts.get(Str.CONVERTING, percent(0.0, 0.0, facts))
            _message.value = try {
                val (decoded, turn) = decodePhoto(context, photo.uri) ?: throw IOException(photo.name)
                val upright = upright(decoded, turn)
                val (width, height) = composedSize(view, upright.width, upright.height)
                val out = createBitmap(width, height)
                val mean = { meanLinearRgb(upright.width, upright.height, upright::getPixel) }
                compose(BitmapSource(upright), view, BitmapSink(out), mean) { rows ->
                    ensureActive()
                    val done = rows.toDouble() / upright.height
                    _message.value = context.texts.get(Str.CONVERTING, percent(done, done, facts))
                }
                savePng(context, out, name)
                context.texts.get(Str.SAVED, name, Gallery.IMAGES.folder)
            } catch (error: IOException) {
                context.texts.get(Str.CONVERSION_FAILED, error.message)
            } catch (error: OutOfMemoryError) {
                context.texts.get(Str.CONVERSION_FAILED, context.texts.get(Str.TOO_LARGE))
            }
        }
    }

    /**
     * Saves the video shown to Movies at its full size and with its sound, as the view shows it now;
     * what the conversion is at, and how the video was written, are told as messages.
     */
    fun convertVideo() {
        val video = source.value as? Source.Video ?: return
        val context = getApplication<Application>()
        val facts = context.texts.facts
        val view = viewToConvert()
        val name = snapshotName(view, now(), suffix = "-full", extension = "mp4")
        startConversion(Dispatchers.Main) {
            _message.value = context.texts.get(Str.CONVERTING, percent(0.0, 0.0, facts))
            // Transformer writes to a path, which the gallery does not give out; the video is copied there after.
            val file = File(context.cacheDir, name)
            _message.value = try {
                val written = convertVideo(context, video.uri, view, file) { done ->
                    _message.value = context.texts.get(Str.CONVERTING, percent(done, done, facts))
                }
                withContext(Dispatchers.IO) {
                    saveToGallery(context, name, Gallery.VIDEOS) { out -> file.inputStream().use { it.copyTo(out) } }
                }
                context.texts.get(Str.SAVED_VIDEO, name, Gallery.VIDEOS.folder, describe(written))
            } catch (error: IOException) {
                context.texts.get(Str.CONVERSION_FAILED, error.message)
            } finally {
                file.delete()
            }
        }
    }

    private val _recorder = MutableStateFlow<Recorder?>(null)

    /** The recording running, which the renderer draws every frame into as well; null while there is none. */
    val recorder: StateFlow<Recorder?> = _recorder.asStateFlow()

    private var recordingName = ""
    private var recordingClock: Job? = null

    /**
     * Records the view as it is shown, every change included, until [stopRecording], and saves it to
     * Movies as dog-<species>-<time>.mp4, as the desktop window names it; how long it has run is told
     * as a message.
     */
    fun startRecording() {
        if (_recorder.value != null) return
        val context = getApplication<Application>()
        val name = snapshotName(view.value, now(), extension = "mp4")
        val file = File(context.cacheDir, name)
        lateinit var recorder: Recorder
        recorder = Recorder(file) { result -> finishRecording(recorder, name, file, result) }
        recordingName = name
        _recorder.value = recorder
        val started = SystemClock.elapsedRealtime()
        recordingClock = viewModelScope.launch {
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - started
                val seconds = elapsed / 1000
                val time = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
                _message.value = context.texts.get(Str.RECORDING, name, time)
                delay(1000 - elapsed % 1000)
            }
        }
    }

    /** Stops recording; the video is finished and saved in the background, as the message tells. */
    fun stopRecording() {
        val recorder = _recorder.value ?: return
        _recorder.value = null
        recordingClock?.cancel()
        _message.value = getApplication<Application>().texts.get(Str.FINISHING, recordingName)
        recorder.stop()
    }

    /** Called on the main thread once [recorder] has finished its video in [file], or failed to. */
    private fun finishRecording(recorder: Recorder, name: String, file: File, result: Result<Written>) {
        if (_recorder.value === recorder) { // it failed while running
            _recorder.value = null
            recordingClock?.cancel()
        }
        val context = getApplication<Application>()
        viewModelScope.launch {
            _message.value = try {
                val written = result.getOrThrow()
                withContext(Dispatchers.IO) {
                    saveToGallery(context, name, Gallery.VIDEOS) { out -> file.inputStream().use { it.copyTo(out) } }
                }
                context.texts.get(Str.SAVED_VIDEO, name, Gallery.VIDEOS.folder, describe(written))
            } catch (error: IOException) {
                context.texts.get(Str.RECORDING_FAILED, error.message)
            } catch (error: IllegalStateException) { // MediaCodec.CodecException among them
                context.texts.get(Str.RECORDING_FAILED, error.message)
            } finally {
                file.delete()
            }
        }
    }

    /** How a video was written, e.g. "H.265 (c2.qti.hevc.encoder), with the original sound". */
    private fun describe(written: Written): String {
        val context = getApplication<Application>()
        val sound = when {
            written.silent -> Str.WRITTEN_SILENT
            written.sound -> Str.WRITTEN_WITH_SOUND
            else -> Str.WRITTEN_WITHOUT_SOUND
        }
        val described = context.texts.get(sound, written.format, written.encoder)
        val (width, height) = written.scaledTo ?: return described
        return context.texts.get(Str.SCALED_TO, described, "$width × $height")
    }

    override fun onCleared() {
        stopRecording()
    }

    /** The bitmap turned as [turn] says: rotated clockwise, then mirrored. */
    private fun upright(bitmap: Bitmap, turn: Pair<Int, Boolean>): Bitmap {
        val (rotation, mirrored) = turn
        if (rotation == 0 && !mirrored) return bitmap
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirrored) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}

private const val VIEW_STATE = "view"
private const val SOURCE_STATE = "source"
private const val FRONT_CAMERA_STATE = "frontCamera"
