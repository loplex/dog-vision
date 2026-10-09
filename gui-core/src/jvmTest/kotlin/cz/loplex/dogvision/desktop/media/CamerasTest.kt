package cz.loplex.dogvision.desktop.media

import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.Facing
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Video4Linux's cameras are read from a sysfs laid out as Linux 6.14 lays out a laptop's USB webcam:
 * each node's device a link to its USB interface, the USB device's port a link to the hub's port,
 * and physical_location where the firmware places them.
 *
 * Windows, where listCameras asks ffmpeg instead, cannot lay that sysfs out,
 * as a file name there holds no colon; the tests that lay one out are left out there.
 */
class CamerasTest {
    @TempDir
    lateinit var sys: File

    /** A USB device [name] with its interface, its port, and the [devicePanel] and [portPanel] given to either. */
    private fun usbDevice(name: String, devicePanel: String? = null, portPanel: String? = null): File {
        val device = File(sys, "devices/pci0000:00/usb5/$name").apply { mkdirs() }
        val port = File(sys, "devices/pci0000:00/usb5/5-0:1.0/usb5-port-$name").apply { mkdirs() }
        Files.createSymbolicLink(File(device, "port").toPath(), device.toPath().relativize(port.toPath()))
        devicePanel?.let { panel(device, it) }
        portPanel?.let { panel(port, it) }
        return File(device, "$name:1.0").apply { mkdirs() }
    }

    private fun panel(directory: File, panel: String) =
        File(directory, "physical_location").apply { mkdirs() }.resolve("panel").writeText("$panel\n")

    /** The node /dev/video[number], of the [index] given, named [name], of the USB [interfaceDirectory]. */
    private fun node(number: Int, name: String, interfaceDirectory: File, index: Int = 0) {
        val node = File(sys, "class/video4linux/video$number").apply { mkdirs() }
        File(node, "name").writeText("$name\n")
        File(node, "index").writeText("$index\n")
        Files.createSymbolicLink(File(node, "device").toPath(), interfaceDirectory.toPath())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun capturesAreListedInTheirNumbersOrderWithoutTheirMetadataNodes() {
        val webcam = usbDevice("5-1")
        node(0, "Integrated Camera: Integrated C", webcam)
        node(1, "Integrated Camera: Integrated C", webcam, index = 1)
        val external = usbDevice("5-2")
        node(10, "USB Camera", external)
        node(2, "IR Camera", usbDevice("5-3"))
        assertEquals(
            listOf(
                CameraOption("0", "Integrated Camera: Integrated C", Facing.UNKNOWN),
                CameraOption("2", "IR Camera", Facing.UNKNOWN),
                CameraOption("10", "USB Camera", Facing.UNKNOWN),
            ),
            videoForLinuxCameras(sys),
        )
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun theFacingIsThePanelTheUsbDeviceOrItsPortIsOn() {
        node(0, "front", usbDevice("5-1", portPanel = "front"))
        node(2, "back", usbDevice("5-2", devicePanel = "back"))
        node(4, "lid", usbDevice("5-3", portPanel = "top"))
        assertEquals(
            listOf(Facing.FRONT, Facing.BACK, Facing.UNKNOWN),
            videoForLinuxCameras(sys).map { it.facing },
        )
    }

    @Test
    fun noVideoForLinuxListsNoCamera() {
        assertEquals(emptyList(), videoForLinuxCameras(File(sys, "missing")))
    }
}
