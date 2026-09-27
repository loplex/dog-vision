package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.cli.Arguments
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Desktop
import java.awt.EventQueue
import java.io.File
import java.net.URI
import java.util.Locale
import kotlin.concurrent.thread

/**
 * What a desktop window shows and does, in no toolkit, as the Python program's `LiveSession` holds it: the source, the
 * view, the language, what failed, and the pictures [renderer] draws; each window only lays it out, so that the Compose
 * window and the Swing one cannot drift apart.
 *
 * Its methods are called on the AWT event thread, and its flows change only there, through [post]: Compose Desktop
 * composes on that thread, and Swing collects on it. It starts on what [arguments] ask for, as the window does.
 *
 * The renderer is made by [makeRenderer] once, the feed of each source by [feed], and ffmpeg installed by
 * [ffmpegInstaller], which [canInstallFfmpeg] says whether to offer where it is missing; the tests give their own of
 * each, and a [post] that runs what it is given there and then.
 */
class LiveSession<I>(
    private val arguments: Arguments,
    makeRenderer: (onPicture: (Picture<I>) -> Unit, onFailure: (String) -> Unit) -> Renderer,
    private val feed: (Source, Renderer, (Failure) -> Unit) -> AutoCloseable = ::startFeed,
    private val ffmpegInstaller: () -> FfmpegInstall = ::installFfmpegOrOpenItsPage,
    private val canInstallFfmpeg: Boolean = onWindows,
    private val post: (() -> Unit) -> Unit = { EventQueue.invokeLater(it) },
    private val systemLanguage: () -> String = { Locale.getDefault().toLanguageTag() },
) : AutoCloseable {
    /**
     * What the window shows besides the picture: [source], [view], [language], a tag or "" for the system's, and
     * [texts] in it; why the source cannot be shown, [sourceFailure], which another source clears, and why nothing can
     * be drawn, [drawFailure], which stays; and how installing ffmpeg is going.
     */
    data class State(
        val source: Source,
        val view: View,
        val language: String,
        val texts: Texts,
        val sourceFailure: Failure? = null,
        val drawFailure: Failure? = null,
        /** Whether ffmpeg is being installed, which takes as long as its download. */
        val installingFfmpeg: Boolean = false,
        /** Why ffmpeg was not installed, the last time it was tried. */
        val ffmpegFailure: Failure? = null,
        private val canInstallFfmpeg: Boolean = false,
    ) {
        /** Why nothing is shown, if something is why: that nothing can be drawn goes before the source's failure. */
        val failure: Failure? get() = drawFailure ?: sourceFailure

        /** Whether the window offers to install ffmpeg: where it is missing, and on Windows, which has none. */
        val offersFfmpeg: Boolean get() = failure?.ffmpegMissing == true && canInstallFfmpeg
    }

    private val mutableState = MutableStateFlow(
        State(
            arguments.file?.let(Source::Media) ?: Source.Camera(arguments.camera),
            arguments.windowView,
            "",
            textsIn(""),
            canInstallFfmpeg = canInstallFfmpeg,
        ),
    )
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val mutablePicture = MutableStateFlow<Picture<I>?>(null)

    /**
     * The picture drawn last, apart from [state], so that pictures coming as fast as a video's frames do not touch the
     * controls; a StateFlow keeps only the latest of them for a collector that is behind.
     */
    val picture: StateFlow<Picture<I>?> = mutablePicture.asStateFlow()

    /** The feed of the source shown, and how many were started, so that a failure of one closed since is not shown. */
    private var running: AutoCloseable? = null
    private var started = 0
    private var closed = false

    private val renderer = makeRenderer(
        { picture -> post { if (!closed) mutablePicture.value = picture } },
        { message -> post { change { copy(drawFailure = Failure { it.get(Str.DRAW_FAILED, message) }) } } },
    )

    init {
        renderer.setView(arguments.windowView)
        start(mutableState.value.source)
    }

    /** Shows [file], a photo or else a video, in place of what is shown. */
    fun openFile(file: File) = start(Source.Media(file))

    /** Shows the camera the command line named, or the first, in place of what is shown. */
    fun openCamera() = start(Source.Camera(arguments.camera))

    /** Changes the view as [change] makes it of the view shown. */
    fun changeView(change: (View) -> View) = setView(change(mutableState.value.view))

    /** Goes back to the view the command line asked for, as the window started with. */
    fun reset() = setView(arguments.windowView)

    /** Words the window in [language], a language tag, or in the system's language for "". */
    fun setLanguage(language: String) = change { copy(language = language, texts = textsIn(language)) }

    /** The area the pictures are laid out on, which the window has given the images. */
    fun setArea(area: Area) = renderer.setArea(area)

    /**
     * Installs ffmpeg on a thread of its own, unless it is being installed already, and starts the source again once it
     * is found, or says why it was not.
     */
    fun installFfmpeg() {
        if (closed || mutableState.value.installingFfmpeg) return
        change { copy(installingFfmpeg = true, ffmpegFailure = null) }
        thread(name = "dog-vision-ffmpeg-install", isDaemon = true) {
            val installed = ffmpegInstaller()
            post {
                val failure = when (installed) {
                    FfmpegInstall.Found -> null
                    FfmpegInstall.NoWinget -> Failure { it.get(Str.NO_WINGET, FfmpegPrograms.DOWNLOAD_PAGE) }
                    is FfmpegInstall.Failed -> Failure { it.get(Str.FFMPEG_NOT_INSTALLED, installed.reason) }
                }
                change { copy(installingFfmpeg = false, ffmpegFailure = failure) }
                if (installed == FfmpegInstall.Found && !closed) start(mutableState.value.source)
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

    /** Closes the source shown, then starts [source]. */
    private fun start(source: Source) {
        if (closed) return
        running?.close()
        val number = ++started
        change { copy(source = source, sourceFailure = null) }
        running = feed(source, renderer) { failure ->
            post { if (number == started && !closed) change { copy(sourceFailure = failure) } }
        }
    }

    private inline fun change(change: State.() -> State) {
        mutableState.value = mutableState.value.change()
    }

    private fun textsIn(language: String): Texts = Texts.forLanguages(listOf(language.ifEmpty(systemLanguage)))

    companion object {
        /** A session drawn by a [GlRenderer], opened as [arguments] ask on Windows, into images [imageMaker] makes. */
        fun <I> drawnOnGpu(arguments: Arguments, imageMaker: ImageMaker<I>): LiveSession<I> =
            LiveSession(arguments, { onPicture, onFailure ->
                GlRenderer(arguments.windowsGl, imageMaker, onPicture, onFailure)
            })
    }
}

/** Installs ffmpeg as [FfmpegPrograms.install] does, and opens ffmpeg's download page where winget is missing. */
private fun installFfmpegOrOpenItsPage(): FfmpegInstall = FfmpegPrograms.install().also { installed ->
    if (installed == FfmpegInstall.NoWinget) {
        runCatching {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(FfmpegPrograms.DOWNLOAD_PAGE))
        }
    }
}
