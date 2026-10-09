package cz.loplex.dogvision.web.media

import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.Facing
import kotlin.test.Test
import kotlin.test.assertEquals

/** The cameras listed from what enumerateDevices gives, before and after the page was allowed one. */
class CameraTest {
    private fun device(kind: String, id: String, label: String, facing: String? = null): dynamic {
        val device: dynamic = js("({})")
        device.kind = kind
        device.deviceId = id
        device.label = label
        if (facing != null) {
            device.getCapabilities = { js("({ facingMode: [facing] })") }
        }
        return device
    }

    @Test
    fun noCameraIsListedBeforeThePageWasAllowedOne() {
        val devices = listOf(device("videoinput", "", ""), device("audioinput", "", ""))
        assertEquals(emptyList(), Camera.listedCameras(devices, running = null))
    }

    @Test
    fun theCamerasAreListedOnceAllowedWithTheirNamesAndFacings() {
        val devices = listOf(
            device("audioinput", "mic", "Microphone"),
            device("videoinput", "back", "Back camera", facing = "environment"),
            device("videoinput", "usb", "USB camera"),
        )
        assertEquals(
            listOf(CameraOption("back", "Back camera", Facing.BACK), CameraOption("usb", "USB camera", Facing.UNKNOWN)),
            Camera.listedCameras(devices, running = null),
        )
    }

    @Test
    fun theCameraRunningIsListedAsItStarted() {
        val running = CameraOption("front", "Front camera", Facing.FRONT)
        val devices = listOf(device("videoinput", "front", "Front camera"), device("videoinput", "back", ""))
        assertEquals(
            listOf(running, CameraOption("back", null, Facing.UNKNOWN)),
            Camera.listedCameras(devices, running),
        )
    }

    @Test
    fun theCameraRunningIsListedAloneWhereTheBrowserNamesNone() {
        val running = CameraOption("front", null, Facing.UNKNOWN)
        assertEquals(listOf(running), Camera.listedCameras(listOf(device("videoinput", "", "")), running))
    }
}
