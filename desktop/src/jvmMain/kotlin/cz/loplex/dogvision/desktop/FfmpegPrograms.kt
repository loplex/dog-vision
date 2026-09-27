package cz.loplex.dogvision.desktop

import java.io.File
import java.io.IOException

/** What installing ffmpeg from the window came to. */
sealed interface FfmpegInstall {
    /** ffmpeg and ffprobe are found, and the feeds run them from where they are. */
    data object Found : FfmpegInstall

    /** winget cannot be run, as on a Windows without Microsoft's App Installer. */
    data object NoWinget : FfmpegInstall

    /** winget ran, and ffmpeg is still not found; [reason] is the last thing winget said. */
    class Failed(val reason: String) : FfmpegInstall
}

/**
 * Where the feeds run ffmpeg and ffprobe from: the PATH the window started with, or, once they are installed from the
 * window, where they were put.
 *
 * Windows has no ffmpeg of its own, so the window offers to install it through winget, the package manager Windows 10
 * and 11 come with, as Gyan's build, which winget unpacks for this user alone and puts on the user's PATH. That PATH is
 * written to the registry, and only programs started after it see it, so ffmpeg is looked for on the PATH the registry
 * holds, as a program started from the Start menu now would get it.
 */
object FfmpegPrograms {
    private val PROGRAMS = listOf("ffmpeg", "ffprobe")

    /** winget's package of Gyan's build of ffmpeg, installed for this user, with nothing asked on the way. */
    private val WINGET = listOf(
        "winget",
        "install",
        "--id",
        "Gyan.FFmpeg",
        "--exact",
        "--source",
        "winget",
        "--scope",
        "user",
        "--disable-interactivity",
        "--accept-package-agreements",
        "--accept-source-agreements",
    )

    /** ffmpeg's page of builds, for where winget is missing. */
    const val DOWNLOAD_PAGE = "https://ffmpeg.org/download.html#build-windows"

    /** Each program's whole path, where it was found after the window started. */
    @Volatile
    private var found = emptyMap<String, String>()

    /** What runs [program]: its whole path, where it was found after the window started, else its name. */
    fun command(program: String): String = found[program] ?: program

    /**
     * Finds ffmpeg on the PATH the registry holds, which it is on where it was installed after the window started, and
     * installs it through winget where it is not; blocks until winget ends, which takes as long as the download.
     */
    fun install(): FfmpegInstall = if (locate()) FfmpegInstall.Found else installThroughWinget()

    /** Installs ffmpeg through winget, and finds it where winget put it. */
    private fun installThroughWinget(): FfmpegInstall {
        val process = try {
            ProcessBuilder(WINGET).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return FfmpegInstall.NoWinget
        }
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        val status = process.waitFor()
        return if (locate()) FfmpegInstall.Found else FfmpegInstall.Failed(lastSaid(output) ?: "status $status")
    }

    /** Whether ffmpeg and ffprobe are both on the PATH the registry holds; they are run from there if they are. */
    private fun locate(): Boolean {
        val (machine, user) = registryPath()
        val programs = find(PROGRAMS, folders("$machine;$user"))
        if (programs.size == PROGRAMS.size) found = programs
        return programs.size == PROGRAMS.size
    }

    /** Each of [programs] that is in one of [folders], as its whole path in the first. */
    internal fun find(programs: List<String>, folders: List<File>): Map<String, String> = programs.mapNotNull { name ->
        folders.map { File(it, "$name.exe") }.firstOrNull(File::isFile)?.let { name to it.path }
    }.toMap()

    /** The folders a Windows PATH lists, which may be quoted, and has empty entries where two semicolons meet. */
    internal fun folders(path: String): List<File> =
        path.split(';').map { it.trim().trim('"') }.filter(String::isNotEmpty).map(::File)

    /**
     * The machine's and the user's PATH as the registry holds them now, their variables expanded, as Windows joins them
     * for a program it starts; empty where PowerShell cannot say.
     */
    private fun registryPath(): Pair<String, String> {
        val script = "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding \$false; " +
            "[Environment]::GetEnvironmentVariable('Path', 'Machine'); " +
            "[Environment]::GetEnvironmentVariable('Path', 'User')"
        val lines = try {
            val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            process.outputStream.close()
            // Read as text, as FfmpegFeed's pipes are: readAllBytes asks a pipe for its position, which Wine refuses.
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            process.waitFor()
            output.removePrefix("﻿").lines()
        } catch (_: IOException) {
            emptyList()
        }
        return lines.getOrElse(0) { "" } to lines.getOrElse(1) { "" }
    }

    /** The last line of [output] with words in it, past the progress bars and spinners that winget draws. */
    internal fun lastSaid(output: String): String? =
        output.split('\r', '\n').map(String::trim).lastOrNull { line -> line.any(Char::isLetter) }
}
