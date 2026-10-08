package cz.loplex.dogvision.desktop

import org.lwjgl.system.APIUtil
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.io.File
import java.io.IOException

/**
 * Where snapshots and recordings go while no folder is named or chosen:
 * the user's pictures, where the system keeps them, rather than the folder the window started in,
 * which for a shortcut may be one it cannot write to, as C:\Program Files is.\
 * That is the Pictures known folder on Windows, which [knownFolder] asks Windows for, ~/Pictures on macOS,
 * and elsewhere XDG_PICTURES_DIR as user-dirs.dirs sets it in $XDG_CONFIG_HOME, else in ~/.config;
 * the home where the system names none.
 */
fun picturesFolder(
    environment: Map<String, String> = System.getenv(),
    osName: String = System.getProperty("os.name"),
    home: String = System.getProperty("user.home"),
    knownFolder: () -> File? = ::windowsPictures,
): File = when {
    osName.startsWith("Windows") -> knownFolder()
    osName.startsWith("Mac") -> File(home, "Pictures")
    else -> xdgPictures(File(configHome(environment, home), "user-dirs.dirs"), home)
} ?: File(home)

/**
 * The folder XDG_PICTURES_DIR is set to in [userDirs], the file xdg-user-dirs writes:
 * the last line setting it, its value quoted, and either "$HOME/…", in [home], or a whole path;
 * null where it is set to neither, or the file cannot be read.
 */
@Suppress("ReturnCount")
internal fun xdgPictures(userDirs: File, home: String): File? {
    val lines = try {
        userDirs.readLines()
    } catch (_: IOException) {
        return null
    }
    val value = lines.mapNotNull { XDG_PICTURES.matchEntire(it.trim())?.groupValues?.get(1) }.lastOrNull()
        ?.replace(ESCAPED, "$1")
        ?: return null
    return when {
        value == $$"$HOME" -> File(home)
        value.startsWith($$"$HOME/") -> File(home, value.removePrefix($$"$HOME/"))
        value.startsWith("/") -> File(value)
        else -> null
    }
}

private val XDG_PICTURES = Regex("""XDG_PICTURES_DIR\s*=\s*"((?:[^"\\]|\\.)*)"""")

/** A character the shell's double quotes escape with a backslash, as xdg-user-dirs writes `"`, `\`, `$` and `` ` ``. */
private val ESCAPED = Regex("""\\(.)""")

/**
 * The Pictures known folder, FOLDERID_Pictures, where Windows says it is now,
 * as a user may have moved it, to OneDrive for one;
 * null where Windows cannot say, or LWJGL's natives, which call it, cannot be loaded.
 */
internal fun windowsPictures(): File? = try {
    APIUtil.apiCreateLibrary("shell32").use { shell32 ->
        APIUtil.apiCreateLibrary("ole32").use { ole32 ->
            val getPath = shell32.getFunctionAddress("SHGetKnownFolderPath")
            val free = ole32.getFunctionAddress("CoTaskMemFree")
            if (getPath == MemoryUtil.NULL || free == MemoryUtil.NULL) null else knownFolder(getPath, free)
        }
    }
} catch (_: LinkageError) {
    // An UnsatisfiedLinkError where a library cannot be loaded: the home then, rather than no window.
    null
}

/** FOLDERID_Pictures through SHGetKnownFolderPath at [getPath], its string freed through CoTaskMemFree at [free]. */
@Suppress("MagicNumber")
private fun knownFolder(getPath: Long, free: Long): File? = MemoryStack.stackPush().use { stack ->
    // {33E28130-4E1E-4676-835A-98395C3BC3BB}, laid out as a GUID is in memory.
    val id = stack.malloc(GUID_BYTES).putInt(0x33E28130).putShort(0x4E1E).putShort(0x4676)
    for (byte in listOf(0x83, 0x5A, 0x98, 0x39, 0x5C, 0x3B, 0xC3, 0xBB)) id.put(byte.toByte())
    id.flip()
    val path = stack.mallocPointer(1)
    val result = JNI.invokePPPI(MemoryUtil.memAddress(id), 0, MemoryUtil.NULL, MemoryUtil.memAddress(path), getPath)
    val string = path[0]
    try {
        if (result == S_OK && string != MemoryUtil.NULL) File(MemoryUtil.memUTF16(string)) else null
    } finally {
        // The string is to be freed whether the call succeeded or not, and CoTaskMemFree takes NULL.
        JNI.invokePV(string, free)
    }
}

private const val GUID_BYTES = 16
private const val S_OK = 0
