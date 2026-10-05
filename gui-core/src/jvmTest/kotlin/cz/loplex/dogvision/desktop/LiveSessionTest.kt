package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.cli.ViewOptions
import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.Facing
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Mirroring
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.layOut
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session both windows lay out:
 * one source at a time, the cameras and their mirroring, what failed worded in the language chosen,
 * ffmpeg offered where it is missing, and the view the command line asked for to go back to.\
 * Its feeds and its renderer are stand-ins that write down what is done to them, so that no GL and no ffmpeg is needed.
 */
class LiveSessionTest {
    /** What was done to the feeds and the renderer, in order. */
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Each feed started's own onFailure, in order. */
    private val feedFailures = mutableListOf<(Failure) -> Unit>()

    private val renderer = object : Renderer {
        val views = mutableListOf<View>()
        var shown = 0

        override fun show(frame: Frame, live: Boolean, mirrored: Boolean) {
            synchronized(this) { shown++ }
            log += if (mirrored) "frame mirrored" else "frame"
        }

        override fun setMirrored(mirrored: Boolean) {
            log += "mirrored $mirrored"
        }

        override fun clear() {
            log += "cleared"
        }

        override fun setView(view: View) {
            views += view
        }

        override fun setArea(area: Area) = Unit

        override fun close() {
            log += "renderer closed"
        }
    }

    /** The renderer each feed started was given, in order. */
    private val feedRenderers = mutableListOf<Renderer>()

    /**
     * A feed that shows nothing until a test shows a frame on its renderer,
     * and writes down that it started and that it closed.
     */
    private val feed: (Source, Renderer, (Failure) -> Unit) -> AutoCloseable = { source, renderer, onFailure ->
        val name = name(source)
        log += "start $name"
        feedFailures += onFailure
        feedRenderers += renderer
        AutoCloseable { log += "close $name" }
    }

    private fun name(source: Source) = when (source) {
        is Source.Media -> source.file.name
        is Source.Camera -> "camera ${source.index}"
        Source.None -> "nothing"
    }

    /** A frame for a feed to show. */
    private val frame = Frame(1, 1, ByteBuffer.allocateDirect(4))

    /** The cameras the session lists. */
    private var cameras = listOf(
        CameraOption("0", "Integrated Camera", Facing.UNKNOWN),
        CameraOption("2", "USB Camera", Facing.BACK),
    )

    private fun session(
        arguments: WindowArguments = WindowArguments(file = File("a.jpg")),
        feed: (Source, Renderer, (Failure) -> Unit) -> AutoCloseable = this.feed,
        installer: () -> FfmpegInstall = { FfmpegInstall.Found },
        downloader: ((Int) -> Unit) -> FfmpegInstall = { FfmpegInstall.Found },
        canInstallFfmpeg: Boolean = true,
        wingetFound: Boolean = true,
    ) = LiveSession<Unit>(
        arguments,
        { _, _ -> renderer },
        feed,
        cameraLister = { cameras },
        inBackground = { it() },
        ffmpegInstaller = installer,
        ffmpegDownloader = downloader,
        canInstallFfmpeg = canInstallFfmpeg,
        wingetFound = wingetFound,
        post = { it() },
        systemLanguage = { "en" },
    )

    @Test
    fun anotherSourceClosesTheOneBefore() {
        val session = session()
        session.openFile(File("b.mp4"))
        session.openCamera()
        assertEquals(listOf("start a.jpg", "close a.jpg", "start b.mp4", "close b.mp4", "start camera 0"), log)
        assertTrue(session.state.value.source is Source.Camera)
    }

    @Test
    fun aFailureOfASourceClosedSinceIsNotShown() {
        val session = session()
        session.openFile(File("b.mp4"))
        feedFailures.first()(Failure { "a.jpg failed" })
        assertNull(session.state.value.failure)
        feedFailures.last()(Failure { "b.mp4 failed" })
        assertEquals("b.mp4 failed", session.state.value.failure?.words(session.state.value.texts))
    }

    @Test
    fun anotherSourceClearsTheFailureOfTheOneBefore() {
        val session = session()
        feedFailures.last()(Failure { "a.jpg failed" })
        session.openFile(File("b.mp4"))
        assertNull(session.state.value.failure)
    }

    @Test
    fun aPhotoReadIsShown() {
        val (session, reader) = sessionReadingAPhoto()
        reader.release()
        reader.join()
        assertEquals(1, renderer.shown)
        session.close()
    }

    @Test
    fun aPhotoReadAfterCloseIsNotShown() {
        val (session, reader) = sessionReadingAPhoto()
        session.close()
        reader.release()
        reader.join()
        assertEquals(0, renderer.shown)
    }

    /** A photo read by startFeed as the window reads one, once [release] lets it, on the thread it is read on. */
    private class Reader {
        private val reading = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private lateinit var thread: Thread

        fun read(): Image {
            thread = Thread.currentThread()
            reading.countDown()
            released.await(WAIT_SECONDS, TimeUnit.SECONDS)
            return Image(2, 2)
        }

        fun release() {
            assertTrue(reading.await(WAIT_SECONDS, TimeUnit.SECONDS), "The photo was never read")
            released.countDown()
        }

        fun join() = thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
    }

    private fun sessionReadingAPhoto(): Pair<LiveSession<Unit>, Reader> {
        val reader = Reader()
        val session = session(feed = { source, renderer, onFailure ->
            startFeed(source, renderer, onFailure) { reader.read() }
        })
        return session to reader
    }

    @Test
    fun aMissingFfmpegIsSaidAndOffered() {
        val missing = Failure(ffmpegMissing = true) { it.get(Str.FFMPEG_MISSING, "ffmpeg") }
        val offering = session(arguments = WindowArguments())
        feedFailures.last()(missing)
        val texts = Texts.of("en")
        assertEquals(texts.get(Str.FFMPEG_MISSING, "ffmpeg"), offering.state.value.failure?.words(texts))
        assertTrue(offering.state.value.offersFfmpeg)
        val notOffering = session(arguments = WindowArguments(), canInstallFfmpeg = false)
        feedFailures.last()(missing)
        assertNotNull(notOffering.state.value.failure)
        assertFalse(notOffering.state.value.offersFfmpeg)
    }

    @Test
    fun installingFfmpegStartsTheSourceAgain() {
        val session = session(arguments = WindowArguments())
        feedFailures.last()(Failure(ffmpegMissing = true) { "missing" })
        session.installFfmpeg()
        awaitInstalled(session)
        assertEquals(listOf("start camera 0", "close camera 0", "start camera 0"), log)
        assertNull(session.state.value.failure)
        assertNull(session.state.value.ffmpegFailure)
    }

    @Test
    fun theSourceStartsAgainBeforeFfmpegIsSaidToBeThere() {
        var session: LiveSession<Unit>? = null
        val getting = Collections.synchronizedList(mutableListOf<Boolean>())
        session = session(
            arguments = WindowArguments(),
            feed = { source, renderer, onFailure ->
                session?.let { getting += it.state.value.gettingFfmpeg }
                feed(source, renderer, onFailure)
            },
        )
        session.installFfmpeg()
        awaitInstalled(session)
        session.downloadFfmpeg()
        awaitInstalled(session)
        assertEquals(listOf(true, true), getting)
    }

    @Test
    fun ffmpegNotInstalledIsSaidAndTheSourceNotStartedAgain() {
        val session = session(arguments = WindowArguments(), installer = { FfmpegInstall.Failed("no network") })
        session.installFfmpeg()
        awaitInstalled(session)
        assertEquals(listOf("start camera 0"), log)
        val texts = Texts.of("en")
        assertEquals(texts.get(Str.FFMPEG_NOT_INSTALLED, "no network"), session.state.value.ffmpegFailure?.words(texts))
    }

    @Test
    fun noWingetNamesFfmpegsDownloadPageAsALink() {
        val session = session(arguments = WindowArguments(), installer = { FfmpegInstall.NoWinget })
        session.installFfmpeg()
        awaitInstalled(session)
        val texts = Texts.of("en")
        val failure = session.state.value.ffmpegFailure
        assertEquals(FfmpegPrograms.DOWNLOAD_PAGE, failure?.link)
        assertEquals(texts.get(Str.NO_WINGET, FfmpegPrograms.DOWNLOAD_PAGE), failure?.words(texts))
    }

    @Test
    fun downloadingFfmpegSaysHowFarItIsAndStartsTheSourceAgain() {
        val percents = Collections.synchronizedList(mutableListOf<Int?>())
        lateinit var session: LiveSession<Unit>
        session = session(
            arguments = WindowArguments(),
            downloader = { onPercent ->
                for (percent in listOf(0, 50, 100)) {
                    onPercent(percent)
                    percents += session.state.value.downloadingFfmpeg
                }
                FfmpegInstall.Found
            },
        )
        feedFailures.last()(Failure(ffmpegMissing = true) { "missing" })
        session.downloadFfmpeg()
        awaitInstalled(session)
        assertEquals(listOf<Int?>(0, 50, 100), percents)
        assertEquals(listOf("start camera 0", "close camera 0", "start camera 0"), log)
        assertNull(session.state.value.ffmpegFailure)
    }

    @Test
    fun ffmpegNotDownloadedIsSaidAndTheSourceNotStartedAgain() {
        val session = session(arguments = WindowArguments(), downloader = { FfmpegInstall.Failed("no network") })
        session.downloadFfmpeg()
        awaitInstalled(session)
        assertEquals(listOf("start camera 0"), log)
        val texts = Texts.of("en")
        val said = session.state.value.ffmpegFailure?.words(texts)
        assertEquals(texts.get(Str.FFMPEG_NOT_DOWNLOADED, "no network"), said)
    }

    @Test
    fun wingetIsOfferedBesideTheDownloadOnlyWhereItIsFound() {
        val missing = Failure(ffmpegMissing = true) { "missing" }
        val withWinget = session(arguments = WindowArguments())
        feedFailures.last()(missing)
        assertTrue(withWinget.state.value.offersFfmpeg)
        assertTrue(withWinget.state.value.offersWinget)
        val withoutWinget = session(arguments = WindowArguments(), wingetFound = false)
        feedFailures.last()(missing)
        assertTrue(withoutWinget.state.value.offersFfmpeg)
        assertFalse(withoutWinget.state.value.offersWinget)
    }

    private fun awaitInstalled(session: LiveSession<Unit>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (session.state.value.gettingFfmpeg && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(session.state.value.gettingFfmpeg, "ffmpeg is still being downloaded or installed")
    }

    @Test
    fun theLanguageRewordsAFailureShown() {
        val session = session()
        feedFailures.last()(Failure { it.get(Str.CAMERA_FAILED, "busy") })
        val state = session.state.value
        assertEquals(Texts.of("en").get(Str.CAMERA_FAILED, "busy"), state.failure?.words(state.texts))
        session.setLanguage("cs")
        val czech = session.state.value
        assertEquals("cs", czech.texts.language)
        assertEquals(Texts.of("cs").get(Str.CAMERA_FAILED, "busy"), czech.failure?.words(czech.texts))
        session.setLanguage("")
        assertEquals("en", session.state.value.texts.language)
    }

    @Test
    fun resetReturnsToTheCommandLinesView() {
        val arguments = WindowArguments(File("a.jpg"), view = ViewOptions(Params(species = Species.CAT)))
        val session = session(arguments = arguments)
        session.changeView { it.copy(sideBySide = false, params = it.params.copy(species = Species.HORSE)) }
        assertEquals(Species.HORSE, session.state.value.view.params.species)
        assertEquals(session.state.value.view, renderer.views.last())
        session.reset()
        assertEquals(arguments.windowView, session.state.value.view)
        assertEquals(arguments.windowView, renderer.views.last())
    }

    @Test
    fun thePanelIsShownUntilToggledAndResetLeavesIt() {
        val session = session()
        assertTrue(session.state.value.panelShown)
        session.togglePanel()
        assertFalse(session.state.value.panelShown)
        session.reset()
        assertFalse(session.state.value.panelShown)
        session.togglePanel()
        assertTrue(session.state.value.panelShown)
    }

    @Test
    fun aDrawFailureStaysWhenTheSourceChanges() {
        var failDrawing: (String) -> Unit = {}
        val session = LiveSession<Unit>(
            WindowArguments(),
            { _, onFailure -> renderer.also { failDrawing = onFailure } },
            feed,
            cameraLister = { cameras },
            inBackground = { it() },
            post = { it() },
            systemLanguage = { "en" },
        )
        failDrawing("no EGL")
        session.openFile(File("b.mp4"))
        val state = session.state.value
        assertEquals(Texts.of("en").get(Str.DRAW_FAILED, "no EGL"), state.failure?.words(state.texts))
    }

    @Test
    fun closeStopsTheFeedBeforeTheRenderer() {
        val session = session()
        session.close()
        session.close()
        assertEquals(listOf("start a.jpg", "close a.jpg", "renderer closed"), log)
    }

    @Test
    fun theCamerasAreOfferedOnceListedTheOneShownAsTheListHasIt() {
        val camera = session(arguments = WindowArguments()).state.value.camera
        assertEquals(cameras, camera.cameras)
        assertEquals(cameras[0], camera.shown)
        assertNull(session().state.value.camera.shown, "a file is shown")
    }

    @Test
    fun aCameraNotListedIsShownAsTheCommandLineNamesIt() {
        cameras = emptyList()
        val camera = session(arguments = WindowArguments(camera = 4)).state.value.camera
        assertEquals(CameraOption("4", null, Facing.UNKNOWN), camera.shown)
        assertEquals(listOf(null, camera.shown), camera.offered)
    }

    @Test
    fun offShowsNothingAndTheCameraButtonTheCameraChosenLast() {
        val session = session()
        session.chooseCamera(null)
        assertTrue(session.state.value.source is Source.Media, "Off while a file is shown")
        session.chooseCamera(cameras[1])
        session.chooseCamera(null)
        assertEquals(Source.None, session.state.value.source)
        assertNull(session.state.value.camera.shown)
        session.openFile(File("b.mp4"))
        session.openCamera()
        assertEquals(
            listOf(
                "start a.jpg", "close a.jpg", "start camera 2", "close camera 2", "cleared", "start nothing",
                "close nothing", "start b.mp4", "close b.mp4", "start camera 2",
            ),
            log,
        )
        assertEquals(cameras[1], session.state.value.camera.shown)
    }

    @Test
    fun noPictureIsShownWhileTheCameraIsOff() {
        var onPicture: (Picture<Unit>) -> Unit = {}
        val session = LiveSession(
            WindowArguments(),
            { picture, _ -> renderer.also { onPicture = picture } },
            feed,
            cameraLister = { cameras },
            inBackground = { it() },
            post = { it() },
            systemLanguage = { "en" },
        )
        val picture = Picture(Unit, layOut(1, 1, 1, 1, 1, 0, 0), View(), null)
        onPicture(picture)
        assertEquals(picture, session.picture.value)
        session.chooseCamera(null)
        assertNull(session.picture.value)
        onPicture(picture)
        assertNull(session.picture.value)
    }

    @Test
    fun aCamerasFramesAreMirroredAsChosenAndTheFrameShownAtOnce() {
        val session = session(arguments = WindowArguments())
        feedRenderers.last().show(frame, live = true)
        session.setMirroring(Mirroring.PLAIN)
        feedRenderers.last().show(frame, live = true)
        // Automatic, of a camera that faces away.
        session.setMirroring(Mirroring.AUTO)
        session.chooseCamera(cameras[1])
        feedRenderers.last().show(frame, live = true)
        session.setMirroring(Mirroring.MIRROR)
        assertEquals(
            listOf(
                "start camera 0", "frame mirrored", "mirrored false", "frame", "mirrored true", "close camera 0",
                "start camera 2", "frame", "mirrored true",
            ),
            log,
        )
    }

    @Test
    fun aFilesFramesAreNotMirrored() {
        val session = session()
        session.setMirroring(Mirroring.MIRROR)
        feedRenderers.last().show(frame, live = false)
        assertEquals(listOf("start a.jpg", "frame"), log)
        assertEquals(Mirroring.MIRROR, session.state.value.camera.mirroring)
    }

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
