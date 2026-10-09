package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.common.UsageException
import cz.loplex.dogvision.common.ViewOptions
import cz.loplex.dogvision.common.readCommandLine
import cz.loplex.dogvision.common.usage
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.desktop.gl.WindowsGl
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.awt.Font
import java.io.File
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.UIManager

/** A window's options. */
data class WindowArguments(
    /** The photo or video to show, in place of the camera. */
    val file: File? = null,
    /** The camera the window shows when no file is given, counted from 0: /dev/video followed by it on Linux. */
    val camera: Int = 0,
    val view: ViewOptions = ViewOptions(),
    /** How the window draws on Windows; null for ANGLE, and WGL where ANGLE cannot start. */
    val windowsGl: WindowsGl? = null,
    /** Where snapshots and recordings go, in place of the folder the settings keep, until another is chosen. */
    val outputDir: File? = null,
    val help: Boolean = false,
) {
    /** The view the window starts with: side by side. */
    val windowView: View
        get() = View(view.params, sideBySide = true, compare = view.compare, difference = view.difference)
}

/** Reads [args] as a window takes them. */
fun parseWindowArguments(args: List<String>): WindowArguments =
    readCommandLine(args, WindowArguments(), { copy(file = it) }) { option ->
        when (option.name) {
            "-h", "--help" -> option.flag().let { copy(help = true) }
            "--camera" -> copy(camera = camera(option.name, option.value()))
            "--gl" -> copy(windowsGl = windowsGl(option.name, option.value()))
            "--output-dir" -> copy(outputDir = File(option.value()))
            else -> view.read(option)?.let { copy(view = it) }
        }
    }

/** How to call the window [command], for --help, worded by [texts]; its first line is repeated after a mistake. */
fun windowUsage(texts: Texts, command: String): String = usage(
    texts,
    texts.get(Str.WINDOW_USAGE_SYNOPSIS, command),
    texts.get(Str.WINDOW_USAGE_ABOUT),
    listOf(command to texts.get(Str.USAGE_CAMERA), "$command clip.mp4" to texts.get(Str.USAGE_WINDOW)),
    ViewOptions.usage(texts) + listOf(
        "--camera N" to texts.get(Str.USAGE_OPTION_CAMERA, WindowArguments().camera),
        "--gl API" to texts.get(Str.USAGE_OPTION_GL),
        "--output-dir DIR" to texts.get(Str.USAGE_OPTION_WINDOW_OUTPUT_DIR),
        "-h, --help" to texts.get(Str.USAGE_OPTION_HELP),
    ),
)

/**
 * Reads [args] for the window [command] and opens it with [window], or, for --help or a command line it cannot read,
 * hands the usage or the mistake, worded by [texts], to [say] with whether it is a mistake.\
 * Returns the exit status: 2 for a mistake, as the command line has it.
 */
@Suppress("ReturnCount")
fun runWindow(
    command: String,
    args: List<String>,
    texts: Texts,
    say: (text: String, mistake: Boolean) -> Unit,
    window: (WindowArguments) -> Int,
): Int {
    val arguments = try {
        parseWindowArguments(args)
    } catch (error: UsageException) {
        val synopsis = texts.get(Str.WINDOW_USAGE_SYNOPSIS, command)
        say(synopsis + "\n" + texts.get(Str.USAGE_ERROR, command, error.message(texts)), true)
        return 2
    }
    if (arguments.help) {
        say(windowUsage(texts, command), false)
        return 0
    }
    return window(arguments)
}

/**
 * Says [text] where a window's user reads it:
 * on the terminal on Linux, the usage on the standard output and a mistake on the standard error;
 * on Windows, where a window's launcher has no console, in a dialog titled [command],
 * its text there to be selected and copied.
 */
fun sayFromWindow(command: String, text: String, mistake: Boolean) {
    if (!onWindows) {
        (if (mistake) System.err else System.out).println(text)
        return
    }
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    // As wide as a terminal of 100 columns, the longer lines wrapped, and in Courier New, so that the columns line up.
    val area = JTextArea(text, minOf(text.lines().size + 3, 30), 100).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        caretPosition = 0
    }
    val type = if (mistake) JOptionPane.ERROR_MESSAGE else JOptionPane.PLAIN_MESSAGE
    JOptionPane.showMessageDialog(null, JScrollPane(area), command, type)
}

private fun windowsGl(option: String, value: String): WindowsGl =
    WindowsGl.entries.firstOrNull { it.id == value } ?: throw UsageException(Str.USAGE_NOT_GL, option, value)

private fun camera(option: String, value: String): Int =
    value.toIntOrNull()?.takeIf { it >= 0 } ?: throw UsageException(Str.USAGE_NOT_CAMERA, option, value)
