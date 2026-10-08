package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import org.junit.jupiter.api.io.TempDir
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.event.KeyEvent
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JTable
import javax.swing.JToggleButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which program asks for a file, with what, and what the window is told of it. */
class OpenDialogTest {
    private val both = setOf("kdialog", "zenity")

    private val english = Texts.of("en")

    @TempDir
    lateinit var directory: File

    @Test
    fun kdeGetsKdialogAndAnyOtherDesktopZenity() {
        assertEquals("kdialog", dialogCommand("Open", null, "KDE", both::contains)?.first())
        assertEquals("zenity", dialogCommand("Open", null, "ubuntu:GNOME", both::contains)?.first())
        assertEquals("zenity", dialogCommand("Open", null, null, both::contains)?.first())
    }

    @Test
    fun eitherStandsInForTheOtherAndNeitherLeavesItToAwt() {
        assertEquals("zenity", dialogCommand("Open", null, "KDE", setOf("zenity")::contains)?.first())
        assertEquals("kdialog", dialogCommand("Open", null, "GNOME", setOf("kdialog")::contains)?.first())
        assertNull(dialogCommand("Open", null, "KDE", onPath = { false }))
    }

    @Test
    fun theDialogStartsBesideTheFileShown() {
        // Its absolute path, which on Windows, where the tests run too, is a drive's and has backslashes.
        val shown = File("/home/someone/photos/a.jpg")
        assertEquals(
            listOf("kdialog", "--title", "Open", "--getopenfilename", shown.absolutePath),
            dialogCommand("Open", shown, "KDE", both::contains),
        )
        assertEquals(
            listOf("zenity", "--file-selection", "--title=Open", "--filename=${shown.absolutePath}"),
            dialogCommand("Open", shown, "GNOME", both::contains),
        )
        assertEquals(
            listOf("zenity", "--file-selection", "--title=Open"),
            dialogCommand("Open", null, "", both::contains),
        )
    }

    @Test
    fun aFolderIsPickedInTheFolderShown() {
        val shown = File("/home/someone/shots")
        assertEquals(
            listOf("kdialog", "--title", "Folder", "--getexistingdirectory", shown.absolutePath),
            dialogCommand("Folder", shown, "KDE", both::contains, folder = true),
        )
        assertEquals(
            listOf("zenity", "--file-selection", "--directory", "--title=Folder", "--filename=${shown.absolutePath}/"),
            dialogCommand("Folder", shown, "GNOME", both::contains, folder = true),
        )
    }

    @Test
    fun whatIsPickedIsOpenedAndACancelOpensNothing() {
        assertEquals(listOf(File("/tmp/a.jpg")), picked(run = { "/tmp/a.jpg" }))
        assertEquals(emptyList(), picked(run = { null }))
    }

    @Test
    fun aProgramThatCannotStartLeavesItToAwt() {
        assertEquals(
            listOf(File("/tmp/b.mp4")),
            picked(run = { throw IOException("No such file") }, fallback = File("/tmp/b.mp4")),
        )
    }

    @Test
    fun noProgramLeavesItToAwtThere() {
        val opened = mutableListOf<File>()
        val fallback = { _: Frame?, _: String, _: File?, _: Texts -> File("/tmp/c.jpg") }
        OpenDialog(command = { _, _ -> null }, run = { error("Ran $it") }, fallback = fallback)
            .show(null, "Open", null, english, opened::add)
        assertEquals(listOf(File("/tmp/c.jpg")), opened)
    }

    @Test
    fun aDialogOpenIsNotOpenedAgain() {
        val started = LinkedBlockingQueue<Unit>()
        val closed = CountDownLatch(1)
        val posted = LinkedBlockingQueue<() -> Unit>()
        val dialog = OpenDialog(
            command = { _, _ -> listOf("zenity") },
            run = {
                started.put(Unit)
                closed.await()
                "/tmp/a.jpg"
            },
            fallback = { _, _, _, _ -> error("No fallback") },
            post = posted::put,
        )
        val opened = mutableListOf<File>()
        dialog.show(null, "Open", null, english, opened::add)
        dialog.show(null, "Open", null, english, opened::add)
        assertNotNull(started.poll(5, TimeUnit.SECONDS))
        // A second program would have started by now, while the first one's dialog is still open.
        assertNull(started.poll(500, TimeUnit.MILLISECONDS))
        closed.countDown()
        checkNotNull(posted.poll(5, TimeUnit.SECONDS))()
        assertEquals(listOf(File("/tmp/a.jpg")), opened)
        dialog.show(null, "Open", null, english, opened::add)
        assertNotNull(started.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun runDialogSaysWhatTheProgramPrintedOnSuccessAlone() {
        assertEquals("/tmp/a b.jpg", runDialog(listOf("sh", "-c", "printf '/tmp/a b.jpg\\n'")))
        assertNull(runDialog(listOf("sh", "-c", "printf '/tmp/a.jpg\\n'; exit 1")))
        assertNull(runDialog(listOf("sh", "-c", "exit 0")))
    }

    @Test
    fun swingsChooserOfAFolderSaysAllItShowsInTheTextsLanguage() {
        File(directory, "photos").mkdir()
        val czech = shownWords(Texts.of("cs"))
        val inEnglish = shownWords(english)
        assertTrue("Hledat v:" in czech && "Podrobnosti" in czech && "Název" in czech, "$czech")
        assertTrue("Look In:" in inEnglish && "Details" in inEnglish && "Name" in inEnglish, "$inEnglish")
        assertEquals(emptySet(), czech intersect inEnglish - "photos")
    }

    @Test
    fun aLabelsLetterMarkedByAnAmpersandIsItsKey() {
        val words = fileChooserWords(Texts.of("cs"))
        assertEquals("Hledat v:", words["FileChooser.lookInLabelText"])
        assertEquals(KeyEvent.VK_H, words["FileChooser.lookInLabelMnemonic"])
        assertEquals("Název složky:", words["FileChooser.folderNameLabelText"])
        assertEquals(KeyEvent.VK_S, words["FileChooser.folderNameLabelMnemonic"])
    }

    /**
     * The words a chooser of a folder in [directory] shows in [texts]:
     * its labels', buttons' and menu entries' texts, tool tips and accessible names,
     * its filters, and the columns of its details, which it is switched to.\
     * Those the system gives in its own language are left out:
     * on Windows the shell's columns past the name, size, type and date,
     * which the JDK titles as the shell's GetDetailsOf does, and a folder's type wherever the system names it.
     */
    private fun shownWords(texts: Texts): Set<String> {
        val chooser = folderChooser("Folder", directory, texts)
        val words = mutableSetOf<String>()
        fun collect(component: Component) {
            if (component is JLabel) words += component.text.orEmpty()
            if (component is AbstractButton) words += component.text.orEmpty()
            if (component is JComponent) {
                words += component.toolTipText.orEmpty()
                component.componentPopupMenu?.let(::collect)
            }
            words += component.accessibleContext?.accessibleName.orEmpty()
            if (component is JMenu) component.menuComponents.forEach(::collect)
            if (component is JTable) {
                val javas = component.columnModel.columns.toList().take(if (onWindows) JAVA_COLUMNS else Int.MAX_VALUE)
                words += javas.map { "${it.headerValue}" }
            }
            if (component is Container) component.components.forEach(::collect)
        }
        val details = texts.get(Str.FILE_CHOOSER_DETAILS)
        components(chooser).filterIsInstance<JToggleButton>().first { it.toolTipText == details }.doClick(0)
        collect(chooser)
        words += chooser.choosableFileFilters.map { it.description }
        if (chooser.fileSystemView.getSystemTypeDescription(directory) == null) {
            words += chooser.ui.getFileView(chooser).getTypeDescription(directory).orEmpty()
        }
        return words.filter(String::isNotBlank).toSet()
    }

    private fun components(component: Component): List<Component> =
        listOf(component) + ((component as? Container)?.components.orEmpty().flatMap(::components))

    /** What the window is told was picked in a dialog whose program is [run], with AWT's dialog picking [fallback]. */
    private fun picked(run: (List<String>) -> String?, fallback: File? = null): List<File> {
        val posted = LinkedBlockingQueue<() -> Unit>()
        val opened = mutableListOf<File>()
        val dialog = OpenDialog(
            command = { _, _ -> listOf("zenity") },
            run = run,
            fallback = { _, _, _, _ -> fallback },
            post = posted::put,
        )
        dialog.show(null, "Open", null, english, opened::add)
        checkNotNull(posted.poll(5, TimeUnit.SECONDS)) { "Nothing posted" }()
        return opened
    }
}

/** How many of the details' columns, on Windows, the JDK titles itself: the name, size, type and date. */
private const val JAVA_COLUMNS = 4
