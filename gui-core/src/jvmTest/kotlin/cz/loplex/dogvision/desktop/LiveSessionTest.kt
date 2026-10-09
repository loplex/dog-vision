package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.common.ViewOptions
import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.ClockTime
import cz.loplex.dogvision.core.Facing
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Mirroring
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.layOut
import cz.loplex.dogvision.core.rgb
import cz.loplex.dogvision.desktop.media.Frame
import cz.loplex.dogvision.desktop.media.RECORDING_FPS
import cz.loplex.dogvision.desktop.media.RecordingSink
import cz.loplex.dogvision.ffmpeg.FfmpegInstall
import cz.loplex.dogvision.ffmpeg.FfmpegPrograms
import cz.loplex.dogvision.texts.MenuEntry
import cz.loplex.dogvision.texts.MenuKey
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import cz.loplex.dogvision.texts.press
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
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

        /** What the renderer has composed, which it hands over when asked; null while nothing is. */
        var composed: List<Image>? = null

        override fun readImages(onImages: (List<Image>?) -> Unit) = onImages(composed)

        /** Who records the images, handed them at once, as the renderer does. */
        var recorder: ((List<Image>) -> Unit)? = null

        override fun record(onImages: ((List<Image>) -> Unit)?) {
            recorder = onImages
            composed?.let { onImages?.invoke(it) }
        }

        override fun close() {
            log += "renderer closed"
        }
    }

    @TempDir
    lateinit var directory: File

    private val settings get() = File(directory, "config/dog-vision/settings.json")

    /** The user's pictures, where snapshots and recordings go while no folder is named or chosen. */
    private val pictures get() = File(directory, "Pictures")

    /** The monotonic clock a recording is timed by, and what ticks its status every second. */
    private val nanos = AtomicLong()
    private var tick: (() -> Unit)? = null

    /** The frames each recording wrote, by its file, and whether it was finished. */
    private val recorded: MutableMap<String, MutableList<Int>> = Collections.synchronizedMap(mutableMapOf())
    private val finished: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val recordingSink = { output: File, _: Int, _: Int ->
        val frames = recorded.getOrPut(output.name) { Collections.synchronizedList(mutableListOf()) }
        object : RecordingSink {
            override val format = "H.264"
            override val encoder = "libx264"

            override fun write(pixels: IntArray) {
                frames += pixels[0]
            }

            override fun close() {
                finished += output.name
            }

            override fun abort() = Unit
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

    @Suppress("LongParameterList")
    private fun session(
        arguments: WindowArguments = WindowArguments(file = File("a.jpg")),
        feed: (Source, Renderer, (Failure) -> Unit) -> AutoCloseable = this.feed,
        installer: () -> FfmpegInstall = { FfmpegInstall.Found },
        downloader: ((Int) -> Unit) -> FfmpegInstall = { FfmpegInstall.Found },
        canInstallFfmpeg: Boolean = true,
        wingetFound: Boolean = true,
        ffmpegFound: () -> Boolean = { true },
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
        systemLanguages = { listOf("en") },
        settingsFile = settings,
        clock = { ClockTime(2026, 10, 8, 9, 5, 7) },
        openRecording = recordingSink,
        nanoTime = nanos::get,
        everySecond = { ticks ->
            tick = ticks
            AutoCloseable { tick = null }
        },
        defaultOutputDir = { pictures },
        ffmpegFound = ffmpegFound,
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
            systemLanguages = { listOf("en") },
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
    fun aCameraPluggedInIsOfferedOnceTheCamerasAreListedAgain() {
        val session = session(arguments = WindowArguments())
        val plugged = CameraOption("4", "Another Camera", Facing.FRONT)
        cameras = cameras + plugged
        assertEquals(listOf(null) + cameras.dropLast(1), session.state.value.camera.offered)
        session.listCamerasAgain()
        assertEquals(listOf(null) + cameras, session.state.value.camera.offered)
    }

    @Test
    fun aListingOfTheCamerasOvertakenByALaterOneOffersNothing() {
        val listings = mutableListOf<() -> Unit>()
        val session = LiveSession<Unit>(
            WindowArguments(),
            { _, _ -> renderer },
            feed,
            cameraLister = { cameras },
            inBackground = { listings += it },
            post = { it() },
            systemLanguages = { listOf("en") },
        )
        listings.removeAt(0)()
        val first = cameras
        session.listCamerasAgain()
        cameras = cameras.take(1)
        session.listCamerasAgain()
        listings.removeAt(listings.lastIndex)()
        cameras = first
        listings.removeAt(listings.lastIndex)()
        assertEquals(listOf(null) + first.take(1), session.state.value.camera.offered)
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
            systemLanguages = { listOf("en") },
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

    @Test
    fun theWindowsMenusOpenQuitAndHoldThePanelAndItsToggle() {
        val session = session()
        var opened = 0
        var quit = 0
        fun menus() = session.menus(session.state.value, open = { opened++ }, chooseOutputDir = {}, quit = { quit++ })
        assertEquals(
            listOf("File", "Camera", "Species", "Simulation", "Acuity", "View", "Language"),
            menus().map { it.label },
        )
        assertTrue(menus().press(MenuKey.OPEN))
        assertTrue(menus().press(MenuKey.QUIT))
        assertEquals(1 to 1, opened to quit)
        assertTrue(menus().press(MenuKey.CONTROLS))
        assertFalse(session.state.value.panelShown)
        assertTrue(menus().press(MenuKey.MENU_BAR))
        assertFalse(session.state.value.menuBarShown)
        val view = menus().single { it.label == "View" }.entries
        assertEquals(listOf("Controls", "Menu bar"), view.take(2).map { (it as MenuEntry.Check).label })
        assertTrue(menus().press(MenuKey.SIDE_BY_SIDE))
        assertFalse(session.state.value.view.sideBySide)
        val camera = menus().first().entries.filterIsInstance<MenuEntry.Action>().single { it.label == "Camera" }
        camera.onSelect()
        assertTrue(session.state.value.source is Source.Camera)
        session.setLanguage("cs")
        assertEquals("Soubor", menus().first().label)
    }

    @Test
    fun aSnapshotIsTheViewsImagesSideBySideNamedAfterTheSpeciesAndTheTime() {
        val folder = File(directory, "shots")
        val session = session(WindowArguments(file = File("a.jpg"), outputDir = folder))
        val left = Image(2, 1, intArrayOf(rgb(200, 10, 255), rgb(0, 128, 64)))
        val right = Image(2, 1, intArrayOf(rgb(1, 2, 3), rgb(250, 251, 252)))
        renderer.composed = listOf(left, right)
        assertTrue(session.menus(session.state.value, {}, {}, {}).press(MenuKey.SNAPSHOT))
        val file = File(folder, "dog-dog-20261008-090507.png")
        val written = ImageIO.read(file)
        assertEquals(4 to 1, written.width to written.height)
        val expected = (left.pixels.toList() + right.pixels.toList()).map { it or 0xFF000000.toInt() }
        assertEquals(expected, written.getRGB(0, 0, 4, 1, null, 0, 4).toList())
        val status = checkNotNull(session.state.value.status)(Texts.of("en"))
        assertEquals("Saved ${file.name} to $folder", status)
    }

    @Test
    fun beforeAnythingIsComposedThereIsNothingToSave() {
        val session = session(WindowArguments(file = File("a.jpg"), outputDir = directory))
        session.saveSnapshot()
        assertEquals("Nothing to save yet", session.state.value.status?.invoke(Texts.of("en")))
        assertEquals(emptyList(), directory.listFiles { file -> file.extension == "png" }?.toList())
    }

    @Test
    fun theOutputFolderIsTheCommandLinesThenTheSettingsThenTheUsersPictures() {
        assertEquals(pictures, session().state.value.outputDir)
        writeSettings(settings, Settings(File(directory, "kept")))
        assertEquals(File(directory, "kept"), session().state.value.outputDir)
        val named = File(directory, "named")
        assertEquals(named, session(WindowArguments(outputDir = named)).state.value.outputDir)
    }

    @Test
    fun aFolderChosenIsKeptForTheNextRun() {
        val session = session(WindowArguments(outputDir = File(directory, "named")))
        val chosen = File(directory, "chosen")
        session.setOutputDir(chosen)
        assertEquals(chosen, session.state.value.outputDir)
        assertEquals(Settings(chosen), readSettings(settings))
        assertEquals("Snapshots and recordings go to $chosen", session.state.value.status?.invoke(Texts.of("en")))
        assertEquals(chosen, session().state.value.outputDir)
    }

    @Test
    fun theMenuBarIsShownUntilToggledAndStaysHiddenForTheNextRun() {
        val texts = Texts.of("en")
        val session = session()
        assertTrue(session.state.value.menuBarShown)
        session.toggleMenuBar()
        assertFalse(session.state.value.menuBarShown)
        assertEquals("The menu bar is hidden; Ctrl+M shows it again", session.state.value.status?.invoke(texts))
        assertFalse(readSettings(settings).menuBarShown)
        assertFalse(session().state.value.menuBarShown)
        session.toggleMenuBar()
        assertTrue(session.state.value.menuBarShown)
        assertEquals(null, session.state.value.status)
        assertTrue(session().state.value.menuBarShown)
    }

    @Test
    fun aMenuBarHiddenButNotSavedSaysSoAndIsHiddenStill() {
        File(directory, "config").writeText("a file where the folder should be")
        val session = session()
        session.toggleMenuBar()
        assertFalse(session.state.value.menuBarShown)
        val status = session.state.value.status?.invoke(Texts.of("en")).orEmpty()
        assertTrue(status.startsWith("Cannot save the settings: "), status)
    }

    @Test
    fun settingsThatCannotBeSavedAreSaidAndTheFolderIsStillUsed() {
        File(directory, "config").writeText("a file where the folder should be")
        val session = session()
        val chosen = File(directory, "chosen")
        session.setOutputDir(chosen)
        assertEquals(chosen, session.state.value.outputDir)
        val status = session.state.value.status?.invoke(Texts.of("en")).orEmpty()
        assertTrue(status.startsWith("Cannot save the settings: "), status)
    }

    @Test
    fun theStatusBarNamesTheFileOrTheCamera() {
        val texts = Texts.of("en")
        assertEquals("a.jpg", session().state.value.sourceName(texts))
        val camera = session(WindowArguments())
        assertEquals("Integrated Camera", camera.state.value.sourceName(texts))
    }

    @Test
    fun aRecordingIsTimedAndSavedAndSaysHow() {
        val session = session(WindowArguments(file = File("a.jpg"), outputDir = directory))
        renderer.composed = listOf(Image(2, 1, intArrayOf(1, 2)), Image(2, 1, intArrayOf(3, 4)))
        val texts = Texts.of("en")
        assertTrue(session.menus(session.state.value, {}, {}, {}).press(MenuKey.RECORD))
        assertTrue(session.state.value.recording)
        val name = "dog-dog-20261008-090507.mp4"
        assertEquals("Recording $name: 0:00", session.state.value.recordingStatus?.invoke(texts))
        Thread.sleep(SETTLE_MILLIS)
        nanos.set(65 * SECOND)
        checkNotNull(tick)()
        assertEquals("Recording $name: 1:05", session.state.value.recordingStatus?.invoke(texts))
        session.toggleRecording()
        assertFalse(session.state.value.recording)
        assertEquals(listOf(name), finished.toList())
        assertEquals(65 * RECORDING_FPS, recorded.getValue(name).size)
        assertEquals(setOf(1), recorded.getValue(name).toSet())
        assertEquals(
            "Saved $name to $directory: H.264 (libx264)",
            session.state.value.recordingStatus?.invoke(texts),
        )
        assertNull(renderer.recorder)
        assertNull(tick)
    }

    @Test
    fun whileRecordingNothingChangesTheVideosSize() {
        val session = session(WindowArguments(file = File("a.jpg"), outputDir = directory))
        renderer.composed = listOf(Image(1, 1, intArrayOf(1)), Image(1, 1, intArrayOf(2)))
        session.toggleRecording()
        log.clear()
        session.openFile(File("b.jpg"))
        session.openCamera()
        session.chooseCamera(cameras.first())
        session.changeView { it.copy(sideBySide = false) }
        session.changeView { it.copy(difference = true) }
        assertEquals(emptyList(), log.toList())
        assertTrue(session.state.value.view.sideBySide)
        assertFalse(session.state.value.view.difference)
        session.changeView { it.copy(params = it.params.copy(species = Species.CAT)) }
        assertEquals(Species.CAT, session.state.value.view.params.species)
        val menus = session.menus(session.state.value, {}, {}, {})
        assertFalse(menus.press(MenuKey.OPEN))
        assertFalse(menus.press(MenuKey.SIDE_BY_SIDE))
        session.toggleRecording()
        session.openFile(File("b.jpg"))
        assertEquals(
            listOf("close a.jpg", "start b.jpg"),
            log.filter {
                it.startsWith("close") || it.startsWith("start")
            },
        )
    }

    @Test
    fun aRecordingWithoutAFrameSaysItFailed() {
        val session = session(WindowArguments(file = File("a.jpg"), outputDir = directory))
        session.toggleRecording()
        session.toggleRecording()
        assertEquals(
            "Recording failed: No frame was shown while recording",
            session.state.value.recordingStatus?.invoke(Texts.of("en")),
        )
    }

    @Test
    fun withoutFfmpegNothingIsRecordedAndFfmpegIsOffered() {
        var found = false
        val session = session(WindowArguments(file = File("a.jpg"), outputDir = directory), ffmpegFound = { found })
        renderer.composed = listOf(Image(1, 1, intArrayOf(5)))
        session.toggleRecording()
        assertFalse(session.state.value.recording)
        assertNull(session.state.value.recordingStatus)
        assertNull(renderer.recorder)
        assertNull(tick)
        val texts = Texts.of("en")
        assertEquals(texts.get(Str.FFMPEG_MISSING, "ffmpeg"), session.state.value.failure?.words(texts))
        assertTrue(session.state.value.offersFfmpeg)
        found = true
        session.downloadFfmpeg()
        awaitInstalled(session)
        assertNull(session.state.value.failure)
        assertEquals(listOf("start a.jpg", "close a.jpg", "start a.jpg"), log.filter { it.contains("a.jpg") })
        session.toggleRecording()
        assertTrue(session.state.value.recording)
        session.toggleRecording()
    }

    @Test
    fun ffmpegFoundOutsideTheWindowClearsItsFailureAsItRecords() {
        var found = false
        val session = session(WindowArguments(file = File("a.jpg"), outputDir = directory), ffmpegFound = { found })
        renderer.composed = listOf(Image(1, 1, intArrayOf(5)))
        session.toggleRecording()
        assertEquals(true, session.state.value.failure?.ffmpegMissing)
        found = true
        session.toggleRecording()
        assertNull(session.state.value.failure)
        assertTrue(session.state.value.recording)
        assertEquals(listOf("start a.jpg", "close a.jpg", "start a.jpg"), log.filter { it.contains("a.jpg") })
        session.toggleRecording()
    }

    @Test
    fun closingTheWindowFinishesTheRecording() {
        val session = session(WindowArguments(file = File("a.jpg"), outputDir = directory))
        renderer.composed = listOf(Image(1, 1, intArrayOf(5)))
        session.toggleRecording()
        Thread.sleep(SETTLE_MILLIS)
        session.close()
        assertEquals(listOf("dog-dog-20261008-090507.mp4"), finished.toList())
    }

    @Test
    fun theFileShownIsConvertedNextToItOrIntoTheOutputFolder() {
        val photo = File(directory, "photo.png")
        val pixels = BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(pixels, "png", photo)
        val out = File(directory, "out")
        val session = session(WindowArguments(file = photo, outputDir = out))
        val texts = Texts.of("en")
        assertTrue(session.menus(session.state.value, {}, {}, {}).press(MenuKey.CONVERT))
        val beside = File(directory, "photo.dog.png")
        assertTrue(beside.isFile)
        assertFalse(session.state.value.converting)
        assertEquals("Wrote $beside", session.state.value.conversionStatus?.invoke(texts))
        session.setConvertToOutputDir(true)
        assertTrue(readSettings(settings).convertToOutputDir)
        session.convertSource()
        assertTrue(File(out, "photo.dog.png").isFile)
        assertTrue(session().state.value.convertToOutputDir)
    }

    @Test
    fun aFileThatCannotBeConvertedSaysWhy() {
        val text = File(directory, "notes.txt").apply { writeText("no photo") }
        val session = session(WindowArguments(file = text))
        session.convertSource()
        val status = session.state.value.conversionStatus?.invoke(Texts.of("en")).orEmpty()
        assertTrue(status.startsWith("Cannot ") && "notes.txt" in status, status)
        assertFalse(File(directory, "notes.dog.png").exists())
    }

    @Test
    fun theCameraIsNotConverted() {
        val session = session(WindowArguments())
        assertFalse(session.menus(session.state.value, {}, {}, {}).press(MenuKey.CONVERT))
        session.convertSource()
        assertNull(session.state.value.conversionStatus)
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val SECOND = 1_000_000_000L
        const val SETTLE_MILLIS = 200L
    }
}
