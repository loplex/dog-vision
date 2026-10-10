package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.common.Conversion
import cz.loplex.dogvision.common.ConversionException
import cz.loplex.dogvision.common.Converted
import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.CameraChoice
import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.ClockTime
import cz.loplex.dogvision.core.Facing
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Mirroring
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.snapshotName
import cz.loplex.dogvision.core.stitch
import cz.loplex.dogvision.desktop.media.Frame
import cz.loplex.dogvision.desktop.media.Recorder
import cz.loplex.dogvision.desktop.media.RecordingSink
import cz.loplex.dogvision.desktop.media.ffmpegRecording
import cz.loplex.dogvision.desktop.media.ffmpegRuns
import cz.loplex.dogvision.desktop.media.listCameras
import cz.loplex.dogvision.ffmpeg.FfmpegDownload
import cz.loplex.dogvision.ffmpeg.FfmpegInstall
import cz.loplex.dogvision.ffmpeg.FfmpegPrograms
import cz.loplex.dogvision.ffmpeg.FfmpegPrograms.DOWNLOAD_PAGE
import cz.loplex.dogvision.ffmpeg.Sound
import cz.loplex.dogvision.texts.PanelActions
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.concurrent.thread

/**
 * What a desktop window shows and does, in no toolkit:
 * the source, the cameras and their mirroring, the view, the language, what failed, and the pictures [renderer] draws;
 * each window only lays it out, so that the Compose window and the Swing one cannot drift apart.
 *
 * Its methods are called on the AWT event thread, and its flows change only there, through [post]:
 * Compose Desktop composes on that thread, and Swing collects on it.\
 * It starts on what [arguments] ask for, as the window does.
 *
 * The renderer is made by [makeRenderer] once,
 * the feed of each source by [feed], the cameras listed by [cameraLister] through [inBackground],
 * ffmpeg downloaded by [ffmpegDownloader] and installed through winget by [ffmpegInstaller],
 * which [canInstallFfmpeg] says whether to offer where it is missing,
 * and [wingetFound] whether to offer winget as well,
 * the output folder, where none is named or chosen, by [defaultOutputDir],
 * and whether ffmpeg, which a recording needs, can be run by [ffmpegFound];
 * the tests give their own of each, and a [post] that runs what it is given there and then.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class LiveSession<I>(
    private val arguments: WindowArguments,
    makeRenderer: (onPicture: (Picture<I>) -> Unit, onFailure: (String) -> Unit) -> Renderer,
    private val feed: (Source, Renderer, (Failure) -> Unit) -> AutoCloseable = ::startFeed,
    private val cameraLister: () -> List<CameraOption> = ::listCameras,
    private val inBackground: (
        () -> Unit,
    ) -> Unit = { thread(name = "dog-vision-background", isDaemon = true) { it() } },
    private val ffmpegInstaller: () -> FfmpegInstall = FfmpegPrograms::install,
    private val ffmpegDownloader: (onPercent: (Int) -> Unit) -> FfmpegInstall = FfmpegDownload::download,
    private val canInstallFfmpeg: Boolean = onWindows,
    wingetFound: Boolean = canInstallFfmpeg && FfmpegPrograms.wingetOnPath(),
    private val post: (() -> Unit) -> Unit = { EventQueue.invokeLater(it) },
    private val systemLanguages: () -> List<String> = { cz.loplex.dogvision.texts.systemLanguages() },
    private val settingsFile: File = settingsFile(),
    private val clock: () -> ClockTime = ::now,
    private val openRecording: (File, Int, Int) -> RecordingSink = ::ffmpegRecording,
    private val nanoTime: () -> Long = System::nanoTime,
    private val everySecond: (() -> Unit) -> AutoCloseable = ::everySecond,
    defaultOutputDir: () -> File = { picturesFolder() },
    private val ffmpegFound: () -> Boolean = ::ffmpegRuns,
) : PanelActions,
    AutoCloseable {
    /**
     * What the window shows besides the picture:
     * [source], the [camera] choice, [view], [language], a tag or "" for the system's, and [texts] in it;
     * why the source cannot be shown, [sourceFailure], which another source clears,
     * and why nothing can be drawn, [drawFailure], which stays; how getting ffmpeg is going;
     * and whether the controls are shown.
     */
    data class State(
        val source: Source,
        /** The cameras, the one shown, none while the source is a file or nothing, and their mirroring. */
        val camera: CameraChoice,
        val view: View,
        val language: String,
        val texts: Texts,
        val sourceFailure: Failure? = null,
        val drawFailure: Failure? = null,
        /** Whether winget is installing ffmpeg, which takes as long as its download. */
        val installingFfmpeg: Boolean = false,
        /** How much of ffmpeg has been downloaded, in percent, while it is being downloaded; null otherwise. */
        val downloadingFfmpeg: Int? = null,
        /** Why ffmpeg was neither downloaded nor installed, the last time either was tried. */
        val ffmpegFailure: Failure? = null,
        /**
         * Whether the panel of controls is shown,
         * or the images have the whole window, as F9 toggles it, kept for the next run.
         */
        val panelShown: Boolean = true,
        /** Whether the window shows its menu bar, as Ctrl+M toggles it, kept for the next run. */
        val menuBarShown: Boolean = true,
        /** Where snapshots and recordings go. */
        val outputDir: File,
        /** What the window said it did last, worded when it is shown, in the language chosen then. */
        val status: ((Texts) -> String)? = null,
        /** Whether the view is being recorded, which locks what would change the size of its images. */
        val recording: Boolean = false,
        /** How the recording under way or the last one stands, worded as [status] is. */
        val recordingStatus: ((Texts) -> String)? = null,
        /** Whether a converted file goes into the output folder, in place of next to its original. */
        val convertToOutputDir: Boolean = false,
        /** Whether the file shown is being converted. */
        val converting: Boolean = false,
        /** How the conversion under way or the last one stands, worded as [status] is. */
        val conversionStatus: ((Texts) -> String)? = null,
        private val canInstallFfmpeg: Boolean = false,
        private val wingetFound: Boolean = false,
    ) {
        /** Why nothing is shown, if something is why: that nothing can be drawn goes before the source's failure. */
        val failure: Failure? get() = drawFailure ?: sourceFailure

        /** Whether the window offers to download ffmpeg: where it is missing, and on Windows, which has none. */
        val offersFfmpeg: Boolean get() = failure?.ffmpegMissing == true && canInstallFfmpeg

        /** Whether it offers to install ffmpeg through winget as well: where it offers ffmpeg, and winget is there. */
        val offersWinget: Boolean get() = offersFfmpeg && wingetFound

        /** Whether ffmpeg is being downloaded or installed, which neither may start again meanwhile. */
        val gettingFfmpeg: Boolean get() = installingFfmpeg || downloadingFfmpeg != null

        /** What is shown, as the status bar names it: the file's name, or the camera's as the controls name it. */
        fun sourceName(texts: Texts): String = when (source) {
            is Source.Media -> source.file.name

            is Source.Camera -> camera.offered.indexOf(camera.shown).takeIf { it > 0 }
                ?.let { texts.cameraNames(camera)[it] } ?: texts.get(Str.NUMBERED_CAMERA, source.index)

            Source.None -> ""
        }
    }

    /** What settings.json holds, as read when the session started and written since. */
    private var settings = readSettings(settingsFile)

    /** The camera shown last, or the one --camera names, which the camera button shows again. */
    private var lastCamera = CameraOption("${arguments.camera}", null, Facing.UNKNOWN)

    private val mutableState = MutableStateFlow(
        State(
            arguments.file?.let(Source::Media) ?: Source.Camera(arguments.camera),
            CameraChoice(shown = lastCamera.takeIf { arguments.file == null }),
            arguments.windowView,
            "",
            textsIn(""),
            canInstallFfmpeg = canInstallFfmpeg,
            wingetFound = wingetFound,
            outputDir = (arguments.outputDir ?: settings.outputDir ?: defaultOutputDir()).absoluteFile,
            convertToOutputDir = settings.convertToOutputDir,
            menuBarShown = settings.menuBarShown,
            panelShown = settings.panelShown,
        ),
    )
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val mutablePicture = MutableStateFlow<Picture<I>?>(null)

    /**
     * The picture drawn last, apart from [state],
     * so that pictures coming as fast as a video's frames do not touch the controls;
     * a StateFlow keeps only the latest of them for a collector that is behind.
     */
    val picture: StateFlow<Picture<I>?> = mutablePicture.asStateFlow()

    /** The feed of the source shown, and how many were started, so that a failure of one closed since is not shown. */
    private var running: AutoCloseable? = null
    private var started = 0

    /** Counts the listings of the cameras, so that one overtaken by a later one offers nothing. */
    private var listings = 0
    private var closed = false

    private val renderer = makeRenderer(
        { picture -> post { if (!closed && mutableState.value.source != Source.None) mutablePicture.value = picture } },
        { message -> post { change { copy(drawFailure = Failure { it.get(Str.DRAW_FAILED, message) }) } } },
    )

    /** Whether a camera's frames are mirrored, as the camera's feed reads it on a thread of its own. */
    @Volatile
    private var cameraMirrored = mutableState.value.camera.mirrored

    /** The renderer a camera's feed shows its frames on, mirrored as the controls choose. */
    private val cameraRenderer = object : Renderer by renderer {
        override fun show(frame: Frame, live: Boolean, mirrored: Boolean) = renderer.show(frame, live, cameraMirrored)
    }

    init {
        renderer.setView(arguments.windowView)
        start(mutableState.value.source)
        listCamerasAgain()
    }

    /** Shows [file], a photo or else a video, in place of what is shown; not while recording, as all that changes. */
    fun openFile(file: File) {
        if (recorder == null) start(Source.Media(file))
    }

    /**
     * Shows the camera shown last, or the one the command line named, or the first, in place of what is shown;
     * the cameras are listed again, as one may have been plugged in since.
     */
    fun openCamera() {
        if (recorder != null) return
        start(Source.Camera(lastCamera.id.toInt()))
        listCamerasAgain()
    }

    /** Turns the camera off while it is shown, as the camera button does, and else shows it as [openCamera] does. */
    fun toggleCamera() {
        if (mutableState.value.source is Source.Camera) chooseCamera(null) else openCamera()
    }

    /**
     * Shows [camera] in place of what is shown, one of those [State.camera] offers;
     * null turns the camera off, and does nothing while a file is shown, which is shown with the camera off already.
     */
    override fun chooseCamera(camera: CameraOption?) {
        if (recorder != null) return
        if (camera == null) {
            if (mutableState.value.source is Source.Camera) start(Source.None)
        } else {
            lastCamera = camera
            start(Source.Camera(camera.id.toInt()))
        }
    }

    /** Mirrors a camera's frames as [mirroring] says, the frame shown at once. */
    override fun setMirroring(mirroring: Mirroring) = changeCamera { copy(mirroring = mirroring) }

    /** Changes the view as [change] makes it of the view shown. */
    override fun changeView(change: (View) -> View) = setView(change(mutableState.value.view))

    /** Goes back to the view the command line asked for, as the window started with. */
    override fun reset() = setView(arguments.windowView)

    /** Words the window in [language], a language tag, or in the system's language for "". */
    override fun setLanguage(language: String) = change { copy(language = language, texts = textsIn(language)) }

    /**
     * Hides the panel of controls if it is shown, and shows it if not,
     * as F9 does, and keeps that in settings.json for the next run;
     * the status says if the settings could not be saved, and is left as it was otherwise.
     */
    fun togglePanel() {
        val shown = !mutableState.value.panelShown
        settings = settings.copy(panelShown = shown)
        val failure = saveSettings()
        val unsaved: ((Texts) -> String)? = failure?.let { error -> { it.get(Str.SETTINGS_UNSAVED, error) } }
        change { copy(panelShown = shown, status = unsaved ?: status) }
    }

    /**
     * Hides the menu bar if it is shown, and shows it if not,
     * as Ctrl+M does, and keeps that in settings.json for the next run;
     * the status says how to show a bar hidden again, or that the settings could not be saved.
     */
    fun toggleMenuBar() {
        val shown = !mutableState.value.menuBarShown
        settings = settings.copy(menuBarShown = shown)
        val failure = saveSettings()
        val status: ((Texts) -> String)? = when {
            failure != null -> { texts -> texts.get(Str.SETTINGS_UNSAVED, failure) }
            shown -> null
            else -> { texts -> texts.get(Str.MENU_BAR_HIDDEN) }
        }
        change { copy(menuBarShown = shown, status = status) }
    }

    /**
     * Saves the images of the view shown as a PNG in the output folder, put together as the window shows them,
     * named after the species and the time, as dog-cat-20260925-105600.png; the status says where, or why not.
     */
    fun saveSnapshot() {
        val view = mutableState.value.view
        val folder = mutableState.value.outputDir
        val name = snapshotName(view, clock())
        renderer.readImages { images ->
            post {
                if (images == null) {
                    change { copy(status = { it.get(Str.NO_FRAME) }) }
                    return@post
                }
                val arrangement = mutablePicture.value?.layout?.arrangement ?: Arrangement.ROW
                inBackground {
                    val saved = runCatching { writePng(stitch(images, arrangement), File(folder, name)) }
                    post {
                        val status: (Texts) -> String = saved.fold(
                            onSuccess = { { texts -> texts.get(Str.SAVED, name, folder) } },
                            onFailure = { error -> { texts -> texts.get(Str.SNAPSHOT_FAILED, error.message) } },
                        )
                        change { copy(status = status) }
                    }
                }
            }
        }
    }

    /**
     * Saves snapshots and recordings in [folder] from now on,
     * in place of a folder the command line named, and keeps it in settings.json for the next run;
     * the status says so, or that the settings could not be saved.
     */
    fun setOutputDir(folder: File) {
        val absolute = folder.absoluteFile
        settings = settings.copy(outputDir = absolute)
        val failure = saveSettings()
        val status: (Texts) -> String = if (failure == null) {
            { it.get(Str.OUTPUT_FOLDER_SET, absolute) }
        } else {
            { it.get(Str.SETTINGS_UNSAVED, failure) }
        }
        change { copy(outputDir = absolute, status = status) }
    }

    /** The conversion under way, and what says it has ended. */
    private var conversion: Conversion? = null
    private var conversionEnded: CountDownLatch? = null

    /**
     * Converts the file shown at full size to the view shown, as the command line converts a file,
     * in the background: next to it, or into the output folder where [State.convertToOutputDir] says so;
     * the conversion status says how far it is, then what it wrote or why it could not.\
     * Nothing while the camera is shown or a conversion runs.
     */
    fun convertSource() {
        val state = mutableState.value
        val file = (state.source as? Source.Media)?.file ?: return
        if (conversion != null) return
        val job = Conversion(file, state.view, state.outputDir.takeIf { state.convertToOutputDir })
        val ended = CountDownLatch(1)
        conversion = job
        conversionEnded = ended
        change { copy(converting = true, conversionStatus = converting(0)) }
        inBackground {
            val result = runCatching {
                job.run { percent ->
                    post {
                        if (conversion ===
                            job
                        ) {
                            change { copy(conversionStatus = converting(percent)) }
                        }
                    }
                }
            }
            ended.countDown()
            post {
                if (conversion === job) conversion = null
                if (!closed) change { copy(converting = false, conversionStatus = converted(result)) }
            }
        }
    }

    /** Stops the conversion under way, which then writes nothing and says nothing. */
    fun cancelConversion() {
        conversion?.cancel()
    }

    /**
     * Puts a converted file into the output folder [on] from now on,
     * in place of next to its original, and keeps it in settings.json;
     * the status says if the settings could not be saved.
     */
    fun setConvertToOutputDir(on: Boolean) {
        settings = settings.copy(convertToOutputDir = on)
        val failure = saveSettings()
        change {
            copy(convertToOutputDir = on, status = failure?.let { error -> { it.get(Str.SETTINGS_UNSAVED, error) } })
        }
    }

    /** Writes the settings, and says why they could not be, if they could not. */
    private fun saveSettings(): String? = try {
        writeSettings(settingsFile, settings)
        null
    } catch (error: IOException) {
        error.message.orEmpty()
    }

    private fun converting(percent: Int): (Texts) -> String = { it.get(Str.CONVERTING, it.get(Str.PERCENT, percent)) }

    /** What a conversion's [result] says: the file written and how, nothing if it was cancelled, or why it failed. */
    private fun converted(result: Result<Converted?>): ((Texts) -> String)? = result.fold(
        onSuccess = { converted ->
            when (converted) {
                null -> null

                is Converted.Photo -> { texts -> texts.get(Str.PHOTO_WRITTEN, converted.file) }

                is Converted.Video -> { texts ->
                    val sound = if (converted.sound == Sound.KEPT) Str.WRITTEN_WITH_SOUND else Str.WRITTEN_WITHOUT_SOUND
                    val how = texts.get(sound, converted.encoder.format, converted.encoder.name)
                    texts.get(Str.VIDEO_WRITTEN, converted.file, how)
                }
            }
        },
        onFailure = { error ->
            if (error is ConversionException) {
                error::message
            } else {
                { texts ->
                    texts.get(Str.CONVERSION_FAILED, error.message)
                }
            }
        },
    )

    /** The recording under way, and what ticks its time in the status. */
    private var recorder: Recorder? = null
    private var ticking: AutoCloseable? = null

    /** Whether ffmpeg is being looked for, before a recording starts. */
    private var findingFfmpeg = false

    /**
     * Starts recording the view into the output folder, named as a snapshot is but for its .mp4,
     * or stops the recording under way, which is then finished in the background, as the recording status says.\
     * A recording starts once ffmpeg is found, in the background;
     * where it cannot be run, nothing is recorded, and the window says so in place of the images,
     * and offers it as where a video cannot be shown without it.\
     * Found once that was said, as where it was installed outside the window since,
     * the source is started again, as getting it in the window does.
     */
    fun toggleRecording() = if (recorder == null) findFfmpegAndRecord() else stopRecording()

    private fun findFfmpegAndRecord() {
        if (findingFfmpeg) return
        findingFfmpeg = true
        inBackground {
            val found = ffmpegFound()
            post {
                findingFfmpeg = false
                when {
                    closed || recorder != null -> Unit

                    found -> {
                        if (mutableState.value.sourceFailure?.ffmpegMissing == true) startAgain()
                        startRecording()
                    }

                    else -> change {
                        copy(sourceFailure = Failure(ffmpegMissing = true) { it.get(Str.FFMPEG_MISSING, "ffmpeg") })
                    }
                }
            }
        }
    }

    private fun startRecording() {
        val state = mutableState.value
        val output = File(state.outputDir, snapshotName(state.view, clock(), extension = "mp4"))
        output.parentFile?.mkdirs()
        // The images are put together as they are shown now, which a recording keeps, as its size cannot change.
        val arrangement = mutablePicture.value?.layout?.arrangement ?: Arrangement.ROW
        val recording = Recorder(output, openRecording, nanoTime)
        recorder = recording
        renderer.record { images -> recording.add { stitch(images, arrangement) } }
        change { copy(recording = true, recordingStatus = running(recording)) }
        ticking =
            everySecond { post { if (recorder === recording) change { copy(recordingStatus = running(recording)) } } }
    }

    private fun stopRecording() {
        val recording = recorder ?: return
        recorder = null
        ticking?.close()
        ticking = null
        renderer.record(null)
        recording.stop()
        change { copy(recording = false, recordingStatus = { it.get(Str.FINISHING, recording.output.name) }) }
        inBackground {
            recording.await(FINISH_MILLIS)
            post { if (!closed) change { copy(recordingStatus = finished(recording)) } }
        }
    }

    /** How long [recording] has run, as its status says it. */
    private fun running(recording: Recorder): (Texts) -> String {
        val seconds = (nanoTime() - recording.startedAt) / NANOS_A_SECOND
        val time = "${seconds / SECONDS_A_MINUTE}:" + "${seconds % SECONDS_A_MINUTE}".padStart(2, '0')
        return { it.get(Str.RECORDING, recording.output.name, time) }
    }

    /** Where [recording] went, written how, or why it failed. */
    private fun finished(recording: Recorder): (Texts) -> String {
        val error = recording.error
        val sink = recording.sink
        val name = recording.output.name
        val folder = recording.output.parentFile
        return when {
            error != null || sink == null -> { texts -> texts.get(Str.RECORDING_FAILED, error?.message) }

            else -> { texts ->
                texts.get(Str.SAVED_VIDEO, name, folder, texts.get(Str.WRITTEN_SILENT, sink.format, sink.encoder))
            }
        }
    }

    /** The area the pictures are laid out on, which the window has given the images. */
    fun setArea(area: Area) = renderer.setArea(area)

    /**
     * Downloads ffmpeg on a thread of its own, unless it is being downloaded or installed already,
     * and starts the source again once it is unpacked, or says why it was not.
     */
    fun downloadFfmpeg() {
        if (closed || mutableState.value.gettingFfmpeg) return
        change { copy(downloadingFfmpeg = 0, ffmpegFailure = null) }
        thread(name = "dog-vision-ffmpeg-download", isDaemon = true) {
            val downloaded = ffmpegDownloader { percent ->
                post { if (!closed) change { copy(downloadingFfmpeg = percent) } }
            }
            post {
                val failure = (downloaded as? FfmpegInstall.Failed)?.let { failed ->
                    Failure { it.get(Str.FFMPEG_NOT_DOWNLOADED, failed.reason) }
                }
                // The source starts again before the state says ffmpeg is there, so whoever sees it there sees that.
                if (downloaded == FfmpegInstall.Found && !closed) startAgain()
                change { copy(downloadingFfmpeg = null, ffmpegFailure = failure) }
            }
        }
    }

    /**
     * Installs ffmpeg through winget on a thread of its own, unless it is being downloaded or installed already,
     * and starts the source again once it is found, or says why it was not.
     */
    fun installFfmpeg() {
        if (closed || mutableState.value.gettingFfmpeg) return
        change { copy(installingFfmpeg = true, ffmpegFailure = null) }
        thread(name = "dog-vision-ffmpeg-install", isDaemon = true) {
            val installed = ffmpegInstaller()
            post {
                val failure = when (installed) {
                    FfmpegInstall.Found -> null
                    FfmpegInstall.NoWinget -> Failure(link = DOWNLOAD_PAGE) { it.get(Str.NO_WINGET, DOWNLOAD_PAGE) }
                    is FfmpegInstall.Failed -> Failure { it.get(Str.FFMPEG_NOT_INSTALLED, installed.reason) }
                }
                // As in downloadFfmpeg, the source starts again before the state says ffmpeg is there.
                if (installed == FfmpegInstall.Found && !closed) startAgain()
                change { copy(installingFfmpeg = false, ffmpegFailure = failure) }
            }
        }
    }

    /** Stops the feed, and then the renderer, which the feed shows its frames on. */
    override fun close() {
        if (closed) return
        conversion?.cancel()
        conversionEnded?.await(CONVERSION_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        recorder?.let { recording ->
            stopRecording()
            recording.await(FINISH_MILLIS)
        }
        closed = true
        running?.close()
        running = null
        renderer.close()
    }

    /** Shows [view], unless it has another number of images while recording, which would change the video's size. */
    private fun setView(view: View) {
        if (recorder != null && view.images != mutableState.value.view.images) return
        change { copy(view = view) }
        renderer.setView(view)
    }

    /** Starts the source shown again, and lists the cameras again, once ffmpeg, which lists Windows's, is there. */
    private fun startAgain() {
        start(mutableState.value.source)
        listCamerasAgain()
    }

    /**
     * Closes the source shown, then starts [source];
     * nothing is drawn for [Source.None], and the pictures drawn before are not shown.
     */
    private fun start(source: Source) {
        if (closed) return
        running?.close()
        val number = ++started
        val camera = (source as? Source.Camera)?.let { shown ->
            mutableState.value.camera.cameras.firstOrNull { it.id == "${shown.index}" } ?: lastCamera
        }
        change { copy(source = source, sourceFailure = null) }
        // The frames of the camera started come mirrored as it is; the frame shown till then stays as it was.
        changeCamera(mirrorShown = false) { copy(shown = camera) }
        if (source == Source.None) {
            renderer.clear()
            mutablePicture.value = null
        }
        running = feed(source, if (source is Source.Camera) cameraRenderer else renderer) { failure ->
            post { if (number == started && !closed) change { copy(sourceFailure = failure) } }
        }
    }

    /**
     * Lists the cameras in the background,
     * and offers them once they are listed, the one shown with what the list says of it;
     * none where they cannot be listed, as where Windows has no ffmpeg yet.\
     * The windows ask for it as their list of cameras drops down, as one may have been plugged in or out since.
     */
    fun listCamerasAgain() {
        val listing = ++listings
        inBackground { offerCameras(listing) }
    }

    /** Lists the cameras, and offers them unless a listing after the [listing]th has begun meanwhile. */
    private fun offerCameras(listing: Int) {
        val cameras = try {
            cameraLister()
        } catch (_: IOException) {
            emptyList()
        }
        post {
            if (!closed && listing == listings) {
                changeCamera {
                    val listed = shown?.let { shown -> cameras.firstOrNull { it.id == shown.id } ?: shown }
                    copy(cameras = cameras, shown = listed)
                }
            }
        }
    }

    /**
     * Changes the camera choice,
     * and, if [mirrorShown], mirrors the camera's frame shown at once where that changes whether it is.
     */
    private fun changeCamera(mirrorShown: Boolean = true, change: CameraChoice.() -> CameraChoice) {
        val before = mutableState.value.camera.mirrored
        change { copy(camera = camera.change()) }
        val camera = mutableState.value.camera
        camera.shown?.let { lastCamera = it }
        cameraMirrored = camera.mirrored
        val changed = camera.mirrored != before
        if (mirrorShown && changed && mutableState.value.source is Source.Camera) renderer.setMirrored(camera.mirrored)
    }

    private inline fun change(change: State.() -> State) {
        mutableState.value = mutableState.value.change()
    }

    private fun textsIn(language: String): Texts =
        Texts.forLanguages(if (language.isEmpty()) systemLanguages() else listOf(language))

    /** [image] as a PNG in [file], whose folder is made if it is missing; IOException where it cannot be written. */
    private fun writePng(image: Image, file: File) {
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        buffered.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        file.parentFile?.mkdirs()
        if (!ImageIO.write(buffered, "png", file)) throw IOException("no PNG writer")
    }

    companion object {
        /** How long a recording is waited for to be finished, when it stops and when the window closes. */
        private const val FINISH_MILLIS = 30_000L

        /** How long a conversion cancelled is waited for to remove its file, when the window closes. */
        private const val CONVERSION_WAIT_MILLIS = 5_000L
        private const val NANOS_A_SECOND = 1_000_000_000L
        private const val SECONDS_A_MINUTE = 60

        /** A session drawn by a [GlRenderer], opened as [arguments] ask on Windows, into images [imageMaker] makes. */
        fun <I> drawnOnGpu(arguments: WindowArguments, imageMaker: ImageMaker<I>): LiveSession<I> =
            LiveSession(arguments, { onPicture, onFailure ->
                GlRenderer(arguments.windowsGl, imageMaker, onPicture, onFailure)
            })
    }
}

/** The time on the local clock now, which names what is saved. */
internal fun now(): ClockTime = LocalDateTime.now().run {
    ClockTime(year, monthValue, dayOfMonth, hour, minute, second)
}

/** Calls [tick] every second on a thread of its own, until the AutoCloseable it returns is closed. */
private fun everySecond(tick: () -> Unit): AutoCloseable {
    val timer = Timer("dog-vision-seconds", true)
    timer.scheduleAtFixedRate(
        object : TimerTask() {
            override fun run() = tick()
        },
        SECOND_MILLIS,
        SECOND_MILLIS,
    )
    return AutoCloseable(timer::cancel)
}

private const val SECOND_MILLIS = 1000L
