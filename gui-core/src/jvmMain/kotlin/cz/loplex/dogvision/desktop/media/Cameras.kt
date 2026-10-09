package cz.loplex.dogvision.desktop.media

import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.Facing
import cz.loplex.dogvision.desktop.onWindows
import cz.loplex.dogvision.ffmpeg.FfmpegMissing
import java.io.File
import java.io.IOException

/**
 * The cameras the window can show, each with the number --camera takes as its id:
 * on Linux the capture nodes /dev/videoN, as [videoForLinuxCameras] lists them;
 * on Windows the DirectShow cameras ffmpeg lists, in its order,
 * where nothing this program reads says where a camera faces.\
 * Throws [FfmpegMissing] on Windows if ffmpeg cannot be run.
 */
fun listCameras(): List<CameraOption> = if (onWindows) {
    FfmpegFeed.directShowCameras().mapIndexed { index, camera -> CameraOption("$index", camera.name, Facing.UNKNOWN) }
} else {
    videoForLinuxCameras()
}

/**
 * The capture nodes /dev/videoN that sysfs under [sys] lists,
 * in their numbers' order, each named as its driver names it.\
 * A node whose index is above 0 is left out:
 * a USB webcam has a second node of the same name, which gives its frames' metadata, not its frames.
 *
 * Where a camera faces is the panel that ACPI places its USB device or its port on,
 * which sysfs gives as physical_location:
 * a camera on the front panel, the display's side, faces the viewer, and one on the back faces away.\
 * A computer whose firmware places it nowhere leaves it unknown.
 */
internal fun videoForLinuxCameras(sys: File = File("/sys")): List<CameraOption> {
    val nodes = File(sys, "class/video4linux").listFiles().orEmpty().mapNotNull { node ->
        node.name.removePrefix("video").toIntOrNull()?.let { it to node }
    }
    return nodes.sortedBy { it.first }.mapNotNull { (number, node) ->
        if ((read(File(node, "index"))?.toIntOrNull() ?: 0) > 0) return@mapNotNull null
        CameraOption("$number", read(File(node, "name")), facing(File(node, "device")))
    }
}

/** Where the camera that is the USB interface [device] faces, as [videoForLinuxCameras] reads it. */
private fun facing(device: File): Facing {
    val usbDevice = device.canonicalFile.parentFile ?: return Facing.UNKNOWN
    val panels = listOf(device, usbDevice, File(usbDevice, "port")).mapNotNull {
        read(File(it, "physical_location/panel"))
    }
    return when (panels.firstOrNull()) {
        "front" -> Facing.FRONT
        "back" -> Facing.BACK
        else -> Facing.UNKNOWN
    }
}

/** The text of the sysfs attribute [file], trimmed, or null where it cannot be read. */
private fun read(file: File): String? = try {
    file.readText().trim().takeIf(String::isNotEmpty)
} catch (_: IOException) {
    null
}
