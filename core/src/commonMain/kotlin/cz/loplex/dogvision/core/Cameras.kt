package cz.loplex.dogvision.core

/** How a camera's image is shown: mirrored, as a mirror shows a face, not mirrored, or as the camera faces. */
enum class Mirroring { MIRROR, PLAIN, AUTO }

/**
 * Where a camera faces: towards the viewer, as a phone's front camera, away from them, as its back one,
 * or unknown, as where the system does not say, which a laptop's webcam often does not.
 */
enum class Facing { FRONT, BACK, UNKNOWN }

/**
 * A camera a program can show: [id], as the program opens it,
 * [name], as the system names it, null where it has none to show, and where it faces.
 */
data class CameraOption(val id: String, val name: String?, val facing: Facing)

/**
 * The cameras there are, the one [shown], null while none is, and the [mirroring] chosen,
 * one for whichever camera is shown: Automatic follows a switch from the front camera to the back one.
 *
 * Automatic mirrors the image unless the camera faces away, as the Android app and the web page have mirrored it:
 * a camera of unknown facing is most likely a webcam facing the viewer.\
 * Where the camera shown does not say where it faces, Automatic cannot be chosen,
 * and a choice of it shows and acts as Mirror, until a camera that says is shown.
 */
data class CameraChoice(
    val cameras: List<CameraOption> = emptyList(),
    val shown: CameraOption? = null,
    val mirroring: Mirroring = Mirroring.AUTO,
) {
    /** Where the camera shown faces, unknown while none is. */
    val facing: Facing get() = shown?.facing ?: Facing.UNKNOWN

    /** Whether Automatic can be chosen: whether the camera shown says where it faces. */
    val automaticAvailable: Boolean get() = facing != Facing.UNKNOWN

    /** The mirroring the controls show as chosen. */
    val shownMirroring: Mirroring
        get() = if (mirroring == Mirroring.AUTO && !automaticAvailable) Mirroring.MIRROR else mirroring

    /** Whether the camera's image is mirrored. */
    val mirrored: Boolean
        get() = when (shownMirroring) {
            Mirroring.MIRROR -> true
            Mirroring.PLAIN -> false
            Mirroring.AUTO -> facing != Facing.BACK
        }

    /**
     * What the controls offer, in order: null, which turns the camera off, then the cameras,
     * and the camera shown at the end if it is not among them, as one opened before the cameras were listed.
     */
    val offered: List<CameraOption?>
        get() = listOf(null) + cameras + listOfNotNull(shown?.takeIf { it !in cameras })

    /** The camera a switch shows: the one after the camera shown, the first after the last or if none is shown. */
    val next: CameraOption?
        get() = cameras.getOrNull((cameras.indexOf(shown) + 1) % cameras.size.coerceAtLeast(1))
}
