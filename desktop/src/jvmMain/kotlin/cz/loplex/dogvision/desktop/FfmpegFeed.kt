package cz.loplex.dogvision.desktop

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Frames that the system's ffmpeg decodes, as raw RGBA through a pipe, [width] x [height] with the top row first,
 * handed to [onFrame] on a thread of its own as they come; the buffer is reused for the next frame once [onFrame]
 * returns. [onEnd] is told why the frames stopped, unless [close] stopped them.
 *
 * ffmpeg decodes, turns a video upright as its file says, and scales the frames down to [PREVIEW_LONGEST_SIDE], so
 * that the frames arrive as the passes take them.
 */
class FfmpegFeed private constructor(
    command: List<String>,
    val width: Int,
    val height: Int,
    private val onFrame: (Frame) -> Unit,
    private val onEnd: (String) -> Unit,
) : AutoCloseable {
    private val process = start(command)

    @Volatile
    private var closed = false

    /** The last lines ffmpeg wrote to its standard error, which say why it stopped. */
    private val errors = ArrayDeque<String>()

    private val errorReader = thread(name = "ffmpeg-errors", isDaemon = true) {
        try {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(errors) {
                    errors.addLast(line)
                    if (errors.size > KEPT_ERROR_LINES) errors.removeFirst()
                }
            }
        } catch (_: IOException) {
            // The stream is closed as the process is stopped.
        }
    }

    private val reader = thread(name = "ffmpeg-frames", isDaemon = true) { read() }

    private fun read() {
        val channel = Channels.newChannel(process.inputStream)
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        try {
            while (true) {
                buffer.clear()
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) return end()
                }
                onFrame(Frame(width, height, buffer.flip()))
            }
        } catch (error: IOException) {
            if (!closed) onEnd(error.message.orEmpty())
        }
    }

    private fun end() {
        val status = process.waitFor()
        errorReader.join(END_WAIT_MILLIS)
        if (closed) return
        val said = synchronized(errors) { errors.lastOrNull() }
        onEnd(said ?: "ffmpeg ended with status $status")
    }

    override fun close() {
        closed = true
        process.destroy()
        if (!process.waitFor(END_WAIT_MILLIS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        reader.join(END_WAIT_MILLIS)
    }

    companion object {
        private const val KEPT_ERROR_LINES = 20
        private const val END_WAIT_MILLIS = 2000L

        /** The size a camera is asked for, as the Android app's and the web page's are. */
        private const val CAMERA_WIDTH = 1280
        private const val CAMERA_HEIGHT = 720
        private const val CAMERA_SIZE = "${CAMERA_WIDTH}x$CAMERA_HEIGHT"

        /**
         * The camera [index], counted from 0, asked for Motion-JPEG at 1280 x 720, which a USB webcam gives at 30
         * frames a second where its raw frames come at 10, and else for whatever it gives: /dev/video[index] through
         * Video4Linux on Linux, and on Windows the DirectShow camera that ffmpeg lists [index]th. Throws IOException if
         * ffmpeg cannot open it.
         */
        fun camera(index: Int, onFrame: (Frame) -> Unit, onEnd: (String) -> Unit): FfmpegFeed {
            val (input, size) = if (onWindows) directShowCamera(index) else videoForLinuxCamera(index)
            return start(input, size, onFrame, onEnd)
        }

        /** ffmpeg's input options for /dev/video[index], and the size it gives. */
        private fun videoForLinuxCamera(index: Int): Pair<List<String>, Pair<Int, Int>> {
            val device = "/dev/video$index"
            val preferred = listOf("-f", "v4l2", "-input_format", "mjpeg", "-video_size", CAMERA_SIZE)
            val any = listOf("-f", "v4l2")
            val (options, size) = try {
                preferred to probe(preferred, device)
            } catch (_: IOException) {
                any to probe(any, device)
            }
            return options + listOf("-i", device) to size
        }

        /**
         * ffmpeg's input options for the DirectShow camera ffmpeg lists [index]th, and the size it gives. ffprobe
         * cannot ask DirectShow for Motion-JPEG, which only ffmpeg's -vcodec does, so ffmpeg reads a frame to see that
         * the camera gives it at the size asked for, as DirectShow refuses a size its camera does not have.
         */
        private fun directShowCamera(index: Int): Pair<List<String>, Pair<Int, Int>> {
            val cameras = directShowCameras()
            val camera = cameras.getOrNull(index)
                ?: throw IOException(
                    if (cameras.isEmpty()) "ffmpeg finds no camera" else "ffmpeg finds ${cameras.size}",
                )
            val device = "video=$camera"
            val preferred = listOf("-f", "dshow", "-vcodec", "mjpeg", "-video_size", CAMERA_SIZE)
            if (readsAFrame(preferred, device)) {
                return preferred + listOf("-i", device) to
                    (CAMERA_WIDTH to CAMERA_HEIGHT)
            }
            val any = listOf("-f", "dshow")
            return any + listOf("-i", device) to probe(any, device)
        }

        /** Whether ffmpeg reads a frame of [input] opened with [options]. */
        private fun readsAFrame(options: List<String>, input: String): Boolean {
            val command =
                listOf("ffmpeg", "-v", "error") + options + listOf("-i", input, "-frames:v", "1", "-f", "null", "-")
            val process = start(command)
            // Read as text, as the other pipes are: readAllBytes asks a pipe for its position, which Wine refuses.
            process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            return process.waitFor() == 0
        }

        /** The DirectShow cameras ffmpeg lists, each as its unique alternative name where it has one. */
        private fun directShowCameras(): List<String> {
            val process = start(listOf("ffmpeg", "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"))
            // The list comes on the standard error, and ffmpeg ends with an error as the dummy input opens nothing.
            val listed = process.errorStream.bufferedReader().readText()
            process.waitFor()
            return directShowCameras(listed)
        }

        /**
         * The video devices in [listed], what ffmpeg's `-list_devices true -f dshow` writes, each as its alternative
         * name where it has one, which tells two cameras of one model apart, else as its name: `"name" (video)` lines,
         * as ffmpeg 4.4 and newer write them, or the names under "DirectShow video devices", as older ones do.
         */
        internal fun directShowCameras(listed: String): List<String> {
            val cameras = mutableListOf<String>()
            var video = false
            for (line in listed.lines()) {
                val said = line.substringAfter("] ", line).trim()
                when {
                    said.startsWith("DirectShow video devices") -> video = true

                    said.startsWith("DirectShow audio devices") -> video = false

                    said.startsWith("Alternative name ") && cameras.isNotEmpty() && video ->
                        cameras[cameras.lastIndex] = said.removePrefix("Alternative name ").trim('"')

                    else -> DEVICE.matchEntire(said)?.let { match ->
                        val kinds = match.groupValues[2]
                        if (kinds.isNotEmpty()) video = "video" in kinds
                        if (video) cameras += match.groupValues[1]
                    }
                }
            }
            return cameras
        }

        /** A device's line: its name in quotes, followed by what it gives in parentheses from ffmpeg 4.4 on. */
        private val DEVICE = Regex(""""([^"]+)"(?: \((.*)\))?""")

        /** The video in [file], played over and over at its own speed, without its sound. */
        fun video(file: File, onFrame: (Frame) -> Unit, onEnd: (String) -> Unit): FfmpegFeed {
            val size = probe(emptyList(), file.path)
            return start(listOf("-re", "-stream_loop", "-1", "-i", file.path), size, onFrame, onEnd)
        }

        private fun start(
            input: List<String>,
            upright: Pair<Int, Int>,
            onFrame: (Frame) -> Unit,
            onEnd: (String) -> Unit,
        ): FfmpegFeed {
            val (width, height) = fitted(upright.first, upright.second)
            val output = listOf("-an", "-sn", "-vf", "scale=$width:$height:flags=area", "-pix_fmt", "rgba")
            val command = listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin") + input + output +
                listOf("-f", "rawvideo", "-")
            return FfmpegFeed(command, width, height, onFrame, onEnd)
        }

        /**
         * The upright size of the first video stream of [input] opened with [options], as ffprobe gives it: a size
         * turned a quarter by the stream's rotation is swapped, as ffmpeg turns its frames. Throws IOException with
         * what ffprobe says if it cannot read one.
         */
        internal fun probe(options: List<String>, input: String): Pair<Int, Int> {
            val command = listOf("ffprobe", "-v", "error") + options + listOf(
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream=width,height:stream_side_data=rotation",
                "-of",
                "default=noprint_wrappers=1",
                input,
            )
            val process = start(command)
            val output = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            val status = process.waitFor()
            val values = output.lines().mapNotNull { line ->
                line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
            }.toMap()
            val width = values["width"]?.toIntOrNull()
            val height = values["height"]?.toIntOrNull()
            if (status != 0 || width == null || height == null || width <= 0 || height <= 0) {
                throw IOException(errors.lineSequence().lastOrNull(String::isNotBlank) ?: "$input has no video")
            }
            val rotation = values["rotation"]?.toDoubleOrNull()?.let { abs(it.toInt()) % 180 } ?: 0
            return if (rotation == 90) height to width else width to height
        }

        /**
         * [command] started, with nothing to read on its standard input, which is closed. Throws [FfmpegMissing] if the
         * program cannot be run.
         */
        internal fun start(command: List<String>): Process = try {
            ProcessBuilder(command).start().apply { outputStream.close() }
        } catch (error: IOException) {
            throw FfmpegMissing(command.first(), error)
        }
    }
}

/** ffmpeg's [program], ffmpeg or ffprobe, which cannot be run, as where it is not installed or not on the PATH. */
class FfmpegMissing(val program: String, cause: IOException) :
    IOException("Cannot run $program: ${cause.message}", cause)
