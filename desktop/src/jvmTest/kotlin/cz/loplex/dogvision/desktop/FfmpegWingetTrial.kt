package cz.loplex.dogvision.desktop

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * On a Windows machine with no ffmpeg, thrown away after, what the window's button does: winget installs Gyan's
 * ffmpeg, which this process's PATH does not have and the user's PATH in the registry then has, and a video plays
 * through it. It changes the machine, so it runs only where DOG_VISION_INSTALL_FFMPEG is set.
 */
@EnabledIfEnvironmentVariable(named = "DOG_VISION_INSTALL_FFMPEG", matches = "1")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FfmpegWingetTrial {
    @TempDir
    lateinit var directory: File

    private val programs = listOf("ffmpeg", "ffprobe")

    @Test
    @Order(1)
    fun wingetInstallsFfmpegOnTheUsersPathAlone() {
        val own = FfmpegPrograms.find(programs, FfmpegPrograms.folders(System.getenv("PATH").orEmpty()))
        assertEquals(emptyMap(), own, "this process's PATH has ffmpeg already")
        val installed = FfmpegPrograms.install()
        assertIs<FfmpegInstall.Found>(installed, (installed as? FfmpegInstall.Failed)?.reason)
        val (machine, user) = FfmpegPrograms.registryPath()
        println("machine PATH: $machine")
        println("user PATH: $user")
        val onUserPath = FfmpegPrograms.find(programs, FfmpegPrograms.folders(user))
        assertEquals(programs.toSet(), onUserPath.keys, "the user's PATH: $user")
        assertEquals(onUserPath["ffmpeg"], FfmpegPrograms.command("ffmpeg"))
        assertEquals(onUserPath["ffprobe"], FfmpegPrograms.command("ffprobe"))
    }

    /** A second press, as where ffmpeg was installed while the window ran: winget finds it installed. */
    @Test
    @Order(2)
    fun wingetRunAgainFindsFfmpegInstalled() {
        val installed = FfmpegPrograms.installThroughWinget()
        assertIs<FfmpegInstall.Found>(installed, (installed as? FfmpegInstall.Failed)?.reason)
    }

    /** A video that the installed ffmpeg makes plays through FfmpegFeed, which runs it from where winget put it. */
    @Test
    @Order(3)
    fun aVideoPlaysThroughTheInstalledFfmpeg() {
        val file = File(directory, "video.mp4")
        val source = listOf("-f", "lavfi", "-i", "testsrc=s=32x16:r=10:d=1", "-pix_fmt", "yuv420p")
        val make = FfmpegFeed.start(listOf("ffmpeg", "-v", "error") + source + file.path)
        val said = make.errorStream.bufferedReader().readText()
        assertEquals(0, make.waitFor(), said)
        val frames = CountDownLatch(5)
        val ended = AtomicReference<String>()
        val feed = FfmpegFeed.video(file, { frames.countDown() }, ended::set)
        try {
            assertTrue(frames.await(20, TimeUnit.SECONDS), "only ${5 - frames.count} frames came")
        } finally {
            feed.close()
        }
        assertEquals(32 to 16, feed.width to feed.height)
        assertNull(ended.get())
        assertNotNull(FfmpegPrograms.command("ffmpeg").takeIf { File(it).isAbsolute })
    }
}
