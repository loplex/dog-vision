package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.cli.Arguments
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.io.File
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
 * The session both windows lay out: one source at a time, what failed worded in the language chosen, ffmpeg offered
 * where it is missing, and the view the command line asked for to go back to. Its feeds and its renderer are stand-ins
 * that write down what is done to them, so that no GL and no ffmpeg is needed.
 */
class LiveSessionTest {
    /** What was done to the feeds and the renderer, in order. */
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Each feed started's own onFailure, in order. */
    private val feedFailures = mutableListOf<(Failure) -> Unit>()

    private val renderer = object : Renderer {
        val views = mutableListOf<View>()
        var shown = 0

        override fun show(frame: Frame, live: Boolean) {
            synchronized(this) { shown++ }
        }

        override fun setView(view: View) {
            views += view
        }

        override fun setArea(area: Area) = Unit

        override fun close() {
            log += "renderer closed"
        }
    }

    /** A feed that shows nothing, and writes down that it started and that it closed. */
    private val feed: (Source, Renderer, (Failure) -> Unit) -> AutoCloseable = { source, _, onFailure ->
        val name = name(source)
        log += "start $name"
        feedFailures += onFailure
        AutoCloseable { log += "close $name" }
    }

    private fun name(source: Source) = when (source) {
        is Source.Media -> source.file.name
        is Source.Camera -> "camera ${source.index}"
    }

    private fun session(
        arguments: Arguments = Arguments(file = File("a.jpg"), window = true),
        feed: (Source, Renderer, (Failure) -> Unit) -> AutoCloseable = this.feed,
        installer: () -> FfmpegInstall = { FfmpegInstall.Found },
        canInstallFfmpeg: Boolean = true,
    ) = LiveSession<Unit>(
        arguments,
        { _, _ -> renderer },
        feed,
        installer,
        canInstallFfmpeg,
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
        val offering = session(arguments = Arguments())
        feedFailures.last()(missing)
        val texts = Texts.of("en")
        assertEquals(texts.get(Str.FFMPEG_MISSING, "ffmpeg"), offering.state.value.failure?.words(texts))
        assertTrue(offering.state.value.offersFfmpeg)
        val notOffering = session(arguments = Arguments(), canInstallFfmpeg = false)
        feedFailures.last()(missing)
        assertNotNull(notOffering.state.value.failure)
        assertFalse(notOffering.state.value.offersFfmpeg)
    }

    @Test
    fun installingFfmpegStartsTheSourceAgain() {
        val session = session(arguments = Arguments())
        feedFailures.last()(Failure(ffmpegMissing = true) { "missing" })
        session.installFfmpeg()
        awaitInstalled(session)
        assertEquals(listOf("start camera 0", "close camera 0", "start camera 0"), log)
        assertNull(session.state.value.failure)
        assertNull(session.state.value.ffmpegFailure)
    }

    @Test
    fun ffmpegNotInstalledIsSaidAndTheSourceNotStartedAgain() {
        val session = session(arguments = Arguments(), installer = { FfmpegInstall.Failed("no network") })
        session.installFfmpeg()
        awaitInstalled(session)
        assertEquals(listOf("start camera 0"), log)
        val texts = Texts.of("en")
        assertEquals(texts.get(Str.FFMPEG_NOT_INSTALLED, "no network"), session.state.value.ffmpegFailure?.words(texts))
    }

    private fun awaitInstalled(session: LiveSession<Unit>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (session.state.value.installingFfmpeg && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(session.state.value.installingFfmpeg, "ffmpeg is still being installed")
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
        val arguments = Arguments(file = File("a.jpg"), window = true, params = Params(species = Species.CAT))
        val session = session(arguments = arguments)
        session.changeView { it.copy(sideBySide = false, params = it.params.copy(species = Species.HORSE)) }
        assertEquals(Species.HORSE, session.state.value.view.params.species)
        assertEquals(session.state.value.view, renderer.views.last())
        session.reset()
        assertEquals(arguments.windowView, session.state.value.view)
        assertEquals(arguments.windowView, renderer.views.last())
    }

    @Test
    fun aDrawFailureStaysWhenTheSourceChanges() {
        var failDrawing: (String) -> Unit = {}
        val session = LiveSession<Unit>(
            Arguments(),
            { _, onFailure -> renderer.also { failDrawing = onFailure } },
            feed,
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

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
