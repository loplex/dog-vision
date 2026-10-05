package cz.loplex.dogvision.swing

import cz.loplex.dogvision.desktop.onWindows
import java.awt.RenderingHints
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.swing.UIManager

/**
 * Whether the system asks applications to be dark, as the Compose window's isSystemInDarkTheme has it:
 * on Linux the desktop portal's `org.freedesktop.appearance` `color-scheme`, which GNOME and KDE both set,
 * and on Windows the registry's `AppsUseLightTheme`; light where the system cannot say.
 */
internal fun systemPrefersDark(): Boolean = if (onWindows) windowsPrefersDark() else portalPrefersDark()

/**
 * Smooths the text at every size where the system has it smoothed as each font's gasp table says,
 * as FlatLaf itself smooths it in the JetBrains runtime on macOS; called once the look and feel is set up.\
 * Windows asks for gasp under its standard smoothing, and Wine has it so: there the window's font is Wine's Tahoma,
 * whose gasp table asks for no smoothing up to 16 pixels, so the window's 11-pixel text would come out jagged.\
 * Windows' default, ClearType, asks for LCD smoothing, which stays.
 */
internal fun smoothTextAtEverySize() {
    if (UIManager.get(RenderingHints.KEY_TEXT_ANTIALIASING) == RenderingHints.VALUE_TEXT_ANTIALIAS_GASP) {
        UIManager.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }
}

private fun portalPrefersDark(): Boolean {
    // ReadOne is the portal's since its version 2; Read, which wraps the value in one more variant, before it.
    val setting = listOf("string:org.freedesktop.appearance", "string:color-scheme")
    val methods = listOf("org.freedesktop.portal.Settings.ReadOne", "org.freedesktop.portal.Settings.Read")
    return methods.firstNotNullOfOrNull { method ->
        val output = output(
            listOf(
                "dbus-send",
                "--session",
                "--print-reply=literal",
                "--reply-timeout=$WAIT_MILLIS",
                "--dest=org.freedesktop.portal.Desktop",
                "/org/freedesktop/portal/desktop",
                method,
            ) + setting,
        )
        portalColourScheme(output)
    } == PORTAL_PREFERS_DARK
}

/** The portal's colour scheme as dbus-send prints it, "variant uint32 1": 1 prefers dark, 2 light, 0 neither. */
internal fun portalColourScheme(printed: String): Int? =
    Regex("""\buint32\s+(\d+)""").find(printed)?.groupValues?.get(1)?.toInt()

private fun windowsPrefersDark(): Boolean = registryPrefersDark(
    output(
        listOf(
            "reg",
            "query",
            """HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize""",
            "/v",
            "AppsUseLightTheme",
        ),
    ),
)

/** Whether what `reg query` prints of AppsUseLightTheme says dark: "AppsUseLightTheme    REG_DWORD    0x0". */
@Suppress("MagicNumber")
internal fun registryPrefersDark(printed: String): Boolean =
    Regex("""AppsUseLightTheme\s+REG_DWORD\s+0x(\p{XDigit}+)""").find(printed)?.groupValues?.get(1)?.toInt(16) == 0

/** What [command] prints, or nothing where it cannot be run or does not end in time. */
private fun output(command: List<String>): String = try {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    process.outputStream.close()
    if (process.waitFor(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
        process.inputStream.bufferedReader().readText()
    } else {
        process.destroyForcibly()
        ""
    }
} catch (_: IOException) {
    ""
}

private const val PORTAL_PREFERS_DARK = 1
private const val WAIT_MILLIS = 2000L
