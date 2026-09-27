package cz.loplex.dogvision.swing

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.util.UIScale
import cz.loplex.dogvision.cli.Arguments
import cz.loplex.dogvision.desktop.LiveSession
import cz.loplex.dogvision.desktop.Source
import cz.loplex.dogvision.texts.Str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.FlowLayout
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.TransferHandler

/**
 * Opens the Swing window on what [arguments] ask for, as the Compose window does: the file given with --window, a photo
 * or else a video, or the camera --camera names; returns once the window is closed. Another file is opened from the
 * system's dialog, from the o key as in the Python program's window, or dropped onto the window; q or Escape closes
 * it. What it shows is a [LiveSession]'s, which the window only lays out.
 */
fun showWindow(arguments: Arguments): Int {
    val closed = CountDownLatch(1)
    SwingUtilities.invokeAndWait { openWindow(arguments, closed::countDown) }
    closed.await()
    return 0
}

/** Opens the window, on the event thread; [onClosed] is told once it is closed and the session with it. */
private fun openWindow(arguments: Arguments, onClosed: () -> Unit) {
    if (systemPrefersDark()) FlatDarkLaf.setup() else FlatLightLaf.setup()
    val session = LiveSession.drawnOnGpu(arguments, ::swingImage)
    val frame = JFrame()
    val preview = Preview(session)
    val controls = Controls(session)
    val open = JButton().apply { addActionListener { openFromDialog(frame, session) } }
    val camera = JButton().apply { addActionListener { session.openCamera() } }
    val column = JPanel(BorderLayout()).apply {
        preferredSize = Dimension(UIScale.scale(COLUMN_WIDTH), 0)
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, UIScale.scale(8), 0)).apply {
                border = BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(8), UIScale.scale(8), 0)
                add(open)
                add(camera)
            },
            BorderLayout.NORTH,
        )
        add(scrolled(controls), BorderLayout.CENTER)
    }
    frame.contentPane.add(preview, BorderLayout.CENTER)
    frame.contentPane.add(column, BorderLayout.EAST)

    fun show(state: LiveSession.State) {
        frame.title = state.texts.get(Str.APP_NAME)
        open.text = state.texts.get(Str.OPEN_MEDIA)
        camera.text = state.texts.get(Str.SHOW_CAMERA)
        controls.show(state)
        preview.show(state)
    }
    show(session.state.value)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    scope.launch { session.state.collect(::show) }
    scope.launch { session.picture.collect { preview.picture = it } }

    keys(frame.rootPane, "O" to { openFromDialog(frame, session) }, "Q" to frame::dispose, "ESCAPE" to frame::dispose)
    frame.transferHandler = FileDrop(session::openFile)
    frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
    frame.addWindowListener(
        object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) {
                scope.cancel()
                session.close()
                onClosed()
            }
        },
    )
    frame.size = Dimension(UIScale.scale(WIDTH), UIScale.scale(HEIGHT))
    frame.isLocationByPlatform = true
    frame.isVisible = true
}

/** Opens the photo or the video picked in the system's dialog, which starts in the folder of the file shown, if any. */
private fun openFromDialog(frame: JFrame, session: LiveSession<*>) {
    val state = session.state.value
    val dialog = FileDialog(frame, state.texts.get(Str.OPEN_MEDIA), FileDialog.LOAD)
    val shown = state.source
    if (shown is Source.Media) dialog.directory = shown.file.absoluteFile.parent
    dialog.isVisible = true
    dialog.files.firstOrNull()?.let(session::openFile)
}

/** Runs each action when its key is pressed anywhere in the window [root] is of, as the Compose window's onKeyEvent. */
private fun keys(root: JComponent, vararg actions: Pair<String, () -> Unit>) {
    for ((key, action) in actions) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), key)
        root.actionMap.put(
            key,
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) = action()
            },
        )
    }
}

/** Opens the first file dropped onto the window through [open], and takes nothing else. */
internal class FileDrop(private val open: (File) -> Unit) : TransferHandler() {
    override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

    override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
        val file = files?.firstOrNull() as? File ?: return false
        open(file)
        return true
    }
}

/**
 * An area's pixels as Swing draws them fastest, premultiplied ARGB in ints: RGBA read big end first is 0xRRGGBBAA,
 * which turned once to the right is 0xAARRGGBB.
 */
internal fun swingImage(pixels: ByteArray, width: Int, height: Int): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
    val data = (image.raster.dataBuffer as DataBufferInt).data
    ByteBuffer.wrap(pixels).asIntBuffer().get(data)
    for (i in data.indices) data[i] = Integer.rotateRight(data[i], 8)
    return image
}

/** The window's size at first, and the controls' width, as the Compose window's. */
private const val WIDTH = 1280
private const val HEIGHT = 800
private const val COLUMN_WIDTH = 380
