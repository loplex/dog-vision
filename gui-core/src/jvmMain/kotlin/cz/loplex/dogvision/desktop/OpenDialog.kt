package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlin.concurrent.thread

/**
 * The dialog a window opens a photo or a video from, or picks a [folder] in,
 * one at a time, which starts beside the file shown, or in the folder shown, if any;
 * zenity 4's starts beside the folder as well, with it selected.
 *
 * On Linux it is kdialog's on KDE and zenity's elsewhere, either where the other is missing,
 * each run as a program of its own, and AWT's FileDialog where neither is on the PATH, and on Windows.\
 * The JDK's GTK dialog comes up below the window on KDE from its second opening on:
 * its window keeps the time of the first one's last use,
 * which KWin's focus stealing prevention takes as older than the click into the window.
 * A program started afresh has no such time, so its dialog comes up on top.\
 * tinyfiledialogs picks its dialog the same way.
 *
 * [command] is what asks for a file, if anything does,
 * [run] runs it and says what was picked, and [fallback] shows AWT's dialog, or Swing's for a folder,
 * which AWT's cannot pick on Linux and Windows, worded in the texts it is given;
 * the tests give their own of each, and a [post] that queues what it is given for the test to run later.
 */
class OpenDialog(
    folder: Boolean = false,
    private val command: (title: String, shown: File?) -> List<String>? = { title, shown ->
        systemCommand(title, shown, folder)
    },
    private val run: (List<String>) -> String? = ::runDialog,
    private val fallback: (parent: Frame?, title: String, shown: File?, texts: Texts) -> File? =
        if (folder) ::swingFolderDialog else { parent, title, shown, _ -> awtDialog(parent, title, shown) },
    private val post: (() -> Unit) -> Unit = { EventQueue.invokeLater(it) },
) {
    /** Whether a program's dialog is open, which a key or a button pressed meanwhile does not open again. */
    private var open = false

    /**
     * Shows the dialog over [parent], titled [title], in the folder of the file [shown], if one is,
     * and tells [onPicked] the file picked, if one is;
     * a program's dialog is waited for on a thread of its own,
     * and [onPicked] told on the event thread, as it is called.\
     * Swing's dialog is worded in [texts].
     */
    fun show(parent: Frame?, title: String, shown: File?, texts: Texts, onPicked: (File) -> Unit) {
        if (open) return
        val command = command(title, shown)
        if (command == null) {
            fallback(parent, title, shown, texts)?.let(onPicked)
            return
        }
        open = true
        thread(name = "dog-vision-open-dialog", isDaemon = true) {
            val picked = runCatching { run(command) }
            post {
                open = false
                picked.fold(
                    onSuccess = { path -> path?.let { onPicked(File(it)) } },
                    onFailure = { fallback(parent, title, shown, texts)?.let(onPicked) },
                )
            }
        }
    }
}

/**
 * The command of kdialog or zenity that asks for a file, or a [folder], in a dialog titled [title],
 * starting beside [shown], or for a folder in it, zenity 4's beside it, if any:
 * the first of kdialog and zenity found by [onPath],
 * kdialog first where [desktops], XDG_CURRENT_DESKTOP's list, names KDE; null where neither is found.
 */
internal fun dialogCommand(
    title: String,
    shown: File?,
    desktops: String?,
    onPath: (String) -> Boolean,
    folder: Boolean = false,
): List<String>? {
    val onKde = desktops.orEmpty().split(':').any { it.equals("KDE", ignoreCase = true) }
    val program = (if (onKde) listOf("kdialog", "zenity") else listOf("zenity", "kdialog")).firstOrNull(onPath)
    // Both open the folder of a file they are given, kdialog with the file picked;
    // zenity 4 opens a folder given alone, with or without a slash at its end, in the folder above it.
    val start = shown?.absolutePath
    return when (program) {
        "kdialog" -> listOf(
            "kdialog",
            "--title",
            title,
            if (folder) "--getexistingdirectory" else "--getopenfilename",
        ) +
            listOfNotNull(start)

        // zenity 4 opens the folder above a folder's path, with that folder selected, slash at its end or not.
        "zenity" -> listOf("zenity", "--file-selection") + listOfNotNull("--directory".takeIf { folder }) +
            listOf("--title=$title") + listOfNotNull(start?.let { if (folder) "--filename=$it/" else "--filename=$it" })

        else -> null
    }
}

/** The dialog's command on this system: kdialog's or zenity's on Linux, where found, and none on Windows. */
private fun systemCommand(title: String, shown: File?, folder: Boolean): List<String>? =
    if (onWindows) null else dialogCommand(title, shown, System.getenv("XDG_CURRENT_DESKTOP"), ::onPath, folder)

/** Whether [program] is an executable file in one of the PATH's folders. */
private fun onPath(program: String): Boolean =
    System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, program).canExecute() }

/**
 * Runs [command] until its dialog is closed, and says the path it printed, or null where nothing was picked,
 * which kdialog and zenity say by an exit status of 1; throws where the program cannot be started.
 */
internal fun runDialog(command: List<String>): String? {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    process.outputStream.close()
    val printed = process.inputStream.bufferedReader().readText().trimEnd('\n')
    return printed.takeIf { process.waitFor() == 0 && it.isNotEmpty() }
}

/**
 * Swing's dialog for a folder over [parent],
 * titled [title], in the folder [shown], if any, worded in [texts], and the one picked.
 */
private fun swingFolderDialog(parent: Frame?, title: String, shown: File?, texts: Texts): File? {
    val chooser = folderChooser(title, shown, texts)
    val picked = chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION
    return chooser.selectedFile.takeIf { picked }
}

/** Swing's chooser of a folder, titled [title], in the folder [shown], if any, worded in [texts]. */
internal fun folderChooser(title: String, shown: File?, texts: Texts): JFileChooser {
    for ((key, value) in fileChooserWords(texts)) UIManager.put(key, value)
    return JFileChooser(shown).apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
}

/** AWT's dialog over [parent], titled [title], in the folder of [shown], if any, and the file picked, if one is. */
private fun awtDialog(parent: Frame?, title: String, shown: File?): File? {
    val dialog = FileDialog(parent, title, FileDialog.LOAD)
    if (shown != null) dialog.directory = shown.absoluteFile.parent
    dialog.isVisible = true
    return dialog.files.firstOrNull()
}

/**
 * UIManager's keys that word Swing's file chooser, each with its words in [texts],
 * which a chooser made afterwards shows: the JDK words it in English and a few other languages, Czech not among them.\
 * They are all a chooser of a folder shows in Metal's, FlatLaf's and Windows's look,
 * its menu, the columns of its details and its errors among them,
 * and a label's text and the key of the letter an & marks in it, which focuses its field with Alt;
 * GTK's look reads keys of its own, which stay as the JDK words them.
 */
internal fun fileChooserWords(texts: Texts): Map<String, Any> {
    val labels = LABELS.flatMap { (key, str) ->
        val marked = texts.get(str)
        val letter = marked.indexOf('&') + 1
        listOf(
            "FileChooser.${key}Text" to marked.removeRange(letter - 1, letter),
            "FileChooser.${key}Mnemonic" to KeyEvent.getExtendedKeyCodeForChar(marked[letter].code),
        )
    }
    return (labels + WORDS.map { (key, str) -> "FileChooser.$key" to texts.get(str) }).toMap()
}

/** The labels of the chooser's fields, each with a letter marked by an &. */
private val LABELS = listOf(
    "lookInLabel" to Str.FILE_CHOOSER_LOOK_IN,
    "fileNameLabel" to Str.FILE_CHOOSER_FILE_NAME,
    "folderNameLabel" to Str.FILE_CHOOSER_FOLDER_NAME,
    "filesOfTypeLabel" to Str.FILE_CHOOSER_FILES_OF_TYPE,
)

/** The rest of what the chooser says, by the key it is under after "FileChooser.". */
private val WORDS = listOf(
    "acceptAllFileFilterText" to Str.FILE_CHOOSER_ALL_FILES,
    "openButtonText" to Str.FILE_CHOOSER_OPEN,
    "openButtonToolTipText" to Str.FILE_CHOOSER_OPEN_FILE_TIP,
    "directoryOpenButtonText" to Str.FILE_CHOOSER_OPEN,
    "directoryOpenButtonToolTipText" to Str.FILE_CHOOSER_OPEN_FOLDER_TIP,
    "cancelButtonText" to Str.FILE_CHOOSER_CANCEL,
    "cancelButtonToolTipText" to Str.FILE_CHOOSER_CANCEL_TIP,
    "upFolderToolTipText" to Str.FILE_CHOOSER_UP_TIP,
    "upFolderAccessibleName" to Str.FILE_CHOOSER_UP,
    "homeFolderToolTipText" to Str.FILE_CHOOSER_HOME,
    "homeFolderAccessibleName" to Str.FILE_CHOOSER_HOME,
    "newFolderToolTipText" to Str.FILE_CHOOSER_NEW_FOLDER_TIP,
    "newFolderAccessibleName" to Str.FILE_CHOOSER_NEW_FOLDER,
    "newFolderActionLabelText" to Str.FILE_CHOOSER_NEW_FOLDER,
    "other.newFolder" to Str.FILE_CHOOSER_NEW_FOLDER,
    "other.newFolder.subsequent" to Str.FILE_CHOOSER_NEW_FOLDER_NEXT,
    "win32.newFolder" to Str.FILE_CHOOSER_NEW_FOLDER,
    "win32.newFolder.subsequent" to Str.FILE_CHOOSER_NEW_FOLDER_NEXT,
    "listViewButtonToolTipText" to Str.FILE_CHOOSER_LIST,
    "listViewButtonAccessibleName" to Str.FILE_CHOOSER_LIST,
    "listViewActionLabelText" to Str.FILE_CHOOSER_LIST,
    "detailsViewButtonToolTipText" to Str.FILE_CHOOSER_DETAILS,
    "detailsViewButtonAccessibleName" to Str.FILE_CHOOSER_DETAILS,
    "detailsViewActionLabelText" to Str.FILE_CHOOSER_DETAILS,
    "viewMenuLabelText" to Str.FILE_CHOOSER_VIEW,
    "viewMenuButtonToolTipText" to Str.FILE_CHOOSER_VIEW_MENU,
    "viewMenuButtonAccessibleName" to Str.FILE_CHOOSER_VIEW_MENU,
    "refreshActionLabelText" to Str.FILE_CHOOSER_REFRESH,
    "filesListAccessibleName" to Str.FILE_CHOOSER_FILES_LIST,
    "filesDetailsAccessibleName" to Str.FILE_CHOOSER_FILES_DETAILS,
    "fileNameHeaderText" to Str.FILE_CHOOSER_NAME_COLUMN,
    "fileSizeHeaderText" to Str.FILE_CHOOSER_SIZE_COLUMN,
    "fileTypeHeaderText" to Str.FILE_CHOOSER_TYPE_COLUMN,
    "fileDateHeaderText" to Str.FILE_CHOOSER_MODIFIED_COLUMN,
    "directoryDescriptionText" to Str.FILE_CHOOSER_FOLDER_TYPE,
    "newFolderErrorText" to Str.FILE_CHOOSER_NEW_FOLDER_ERROR,
    "newFolderParentDoesntExistTitleText" to Str.FILE_CHOOSER_NO_PARENT_TITLE,
    "newFolderParentDoesntExistText" to Str.FILE_CHOOSER_NO_PARENT,
    "renameErrorTitleText" to Str.FILE_CHOOSER_RENAME_ERROR_TITLE,
    "renameErrorText" to Str.FILE_CHOOSER_RENAME_ERROR,
    "renameErrorFileExistsText" to Str.FILE_CHOOSER_RENAME_EXISTS,
)
