package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.core.CameraChoice
import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.Facing
import cz.loplex.dogvision.core.Mirroring
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.desktop.FfmpegPrograms.DOWNLOAD_PAGE
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.EventQueue
import java.io.File
import java.io.IOException
import java.util.Locale
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
 * The renderer is made by [makeRenderer] once, the feed of each source by [feed],
 * the cameras listed by [cameraLister] through [inBackground],
 * ffmpeg downloaded by [ffmpegDownloader] and installed through winget by [ffmpegInstaller],
 * which [canInstallFfmpeg] says whether to offer where it is missing,
 * and [wingetFound] whether to offer winget as well;
 * the tests give their own of each, and a [post] that runs what it is given there and then.
 */
class LiveSession<I>(
    private val arguments: WindowArguments,
    makeRenderer: (onPicture: (Picture<I>) -> Unit, onFailure: (String) -> Unit) -> Renderer,
    private val feed: (Source, Renderer, (Failure) -> Unit) -> AutoCloseable = ::startFeed,
    private val cameraLister: () -> List<CameraOption> = ::listCameras,
    private val inBackground: (() -> Unit) -> Unit = { thread(name = "dog-vision-cameras", isDaemon = true) { it() } },
    private val ffmpegInstaller: () -> FfmpegInstall = FfmpegPrograms::install,
    private val ffmpegDownloader: (onPercent: (Int) -> Unit) -> FfmpegInstall = FfmpegDownload::download,
    private val canInstallFfmpeg: Boolean = onWindows,
    private val wingetFound: Boolean = canInstallFfmpeg && FfmpegPrograms.wingetOnPath(),
    private val post: (() -> Unit) -> Unit = { EventQueue.invokeLater(it) },
    private val systemLanguage: () -> String = { Locale.getDefault().toLanguageTag() },
) : AutoCloseable {
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
        /** Whether the panel of controls is shown, or the images have the whole window, as F9 toggles it. */
        val panelShown: Boolean = true,
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
    }

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

    /** Shows [file], a photo or else a video, in place of what is shown. */
    fun openFile(file: File) = start(Source.Media(file))

    /**
     * Shows the camera shown last, or the one the command line named, or the first, in place of what is shown;
     * the cameras are listed again, as one may have been plugged in since.
     */
    fun openCamera() {
        start(Source.Camera(lastCamera.id.toInt()))
        listCamerasAgain()
    }

    /**
     * Shows [camera] in place of what is shown, one of those [State.camera] offers;
     * null turns the camera off, and does nothing while a file is shown, which is shown with the camera off already.
     */
    fun chooseCamera(camera: CameraOption?) {
        if (camera == null) {
            if (mutableState.value.source is Source.Camera) start(Source.None)
        } else {
            lastCamera = camera
            start(Source.Camera(camera.id.toInt()))
        }
    }

    /** Mirrors a camera's frames as [mirroring] says, the frame shown at once. */
    fun setMirroring(mirroring: Mirroring) = changeCamera { copy(mirroring = mirroring) }

    /** Changes the view as [change] makes it of the view shown. */
    fun changeView(change: (View) -> View) = setView(change(mutableState.value.view))

    /** Goes back to the view the command line asked for, as the window started with. */
    fun reset() = setView(arguments.windowView)

    /** Words the window in [language], a language tag, or in the system's language for "". */
    fun setLanguage(language: String) = change { copy(language = language, texts = textsIn(language)) }

    /** Hides the panel of controls if it is shown, and shows it if not, as F9 does. */
    fun togglePanel() = change { copy(panelShown = !panelShown) }

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
        closed = true
        running?.close()
        running = null
        renderer.close()
    }

    private fun setView(view: View) {
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
     * none where they cannot be listed, as where Windows has no ffmpeg yet.
     */
    private fun listCamerasAgain() = inBackground {
        val cameras = try {
            cameraLister()
        } catch (_: IOException) {
            emptyList()
        }
        post {
            if (!closed) {
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

    private fun textsIn(language: String): Texts = Texts.forLanguages(listOf(language.ifEmpty(systemLanguage)))

    companion object {
        /** A session drawn by a [GlRenderer], opened as [arguments] ask on Windows, into images [imageMaker] makes. */
        fun <I> drawnOnGpu(arguments: WindowArguments, imageMaker: ImageMaker<I>): LiveSession<I> =
            LiveSession(arguments, { onPicture, onFailure ->
                GlRenderer(arguments.windowsGl, imageMaker, onPicture, onFailure)
            })
    }
}
