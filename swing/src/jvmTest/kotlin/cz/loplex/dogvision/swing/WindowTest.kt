package cz.loplex.dogvision.swing

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JPanel
import javax.swing.TransferHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pixels read back as Swing draws them, and a file dropped onto the window. */
class WindowTest {
    @Test
    fun rgbaReadBackBecomesPremultipliedArgb() {
        // Opaque red, then half-transparent green premultiplied, then transparent black, then opaque white.
        val pixels = byteArrayOf(
            0xFF.toByte(), 0, 0, 0xFF.toByte(),
            0, 0x80.toByte(), 0, 0x80.toByte(),
            0, 0, 0, 0,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        val image = swingImage(pixels, 2, 2)
        assertEquals(BufferedImage.TYPE_INT_ARGB_PRE, image.type)
        val data = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
        assertEquals(listOf(0xFFFF0000.toInt(), 0x80008000.toInt(), 0, 0xFFFFFFFF.toInt()), data.toList())
    }

    @Test
    fun theFirstFileDroppedIsOpened() {
        val opened = mutableListOf<File>()
        val drop = FileDrop { opened += it }
        val files = listOf(File("a.mp4"), File("b.jpg"))
        val dropped = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor

            override fun getTransferData(flavor: DataFlavor): Any = files
        }
        assertTrue(drop.importData(TransferHandler.TransferSupport(JPanel(), dropped)))
        assertEquals(listOf(File("a.mp4")), opened)
    }

    @Test
    fun textDroppedIsNotTaken() {
        val drop = FileDrop { error("Opened $it") }
        assertFalse(drop.importData(TransferHandler.TransferSupport(JPanel(), StringSelection("a.mp4"))))
    }
}
