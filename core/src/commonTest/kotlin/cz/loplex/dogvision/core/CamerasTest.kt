package cz.loplex.dogvision.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mirroring chosen resolves against where the camera shown faces, and the cameras are offered and switched. */
class CamerasTest {
    private val back = CameraOption("0", null, Facing.BACK)
    private val front = CameraOption("1", null, Facing.FRONT)
    private val webcam = CameraOption("/dev/video0", "Integrated Camera", Facing.UNKNOWN)

    @Test
    fun automaticMirrorsUnlessTheCameraFacesAway() {
        assertTrue(CameraChoice(shown = front).mirrored)
        assertFalse(CameraChoice(shown = back).mirrored)
        assertTrue(CameraChoice(shown = webcam).mirrored)
    }

    @Test
    fun automaticIsShownAsMirrorWhereTheFacingIsUnknown() {
        val choice = CameraChoice(listOf(webcam, back), shown = webcam)
        assertFalse(choice.automaticAvailable)
        assertEquals(Mirroring.MIRROR, choice.shownMirroring)
        val switched = choice.copy(shown = back)
        assertTrue(switched.automaticAvailable)
        assertEquals(Mirroring.AUTO, switched.shownMirroring)
        assertFalse(switched.mirrored)
    }

    @Test
    fun mirrorAndPlainHoldWhereverTheCameraFaces() {
        for (camera in listOf(front, back, webcam, null)) {
            assertTrue(CameraChoice(shown = camera, mirroring = Mirroring.MIRROR).mirrored, "$camera")
            assertFalse(CameraChoice(shown = camera, mirroring = Mirroring.PLAIN).mirrored, "$camera")
            assertEquals(Mirroring.PLAIN, CameraChoice(shown = camera, mirroring = Mirroring.PLAIN).shownMirroring)
        }
    }

    @Test
    fun offersOffFirstAndACameraShownThatIsNotListedLast() {
        assertEquals(listOf(null, back, front), CameraChoice(listOf(back, front), shown = front).offered)
        assertEquals(listOf(null, back, webcam), CameraChoice(listOf(back), shown = webcam).offered)
        assertEquals(listOf<CameraOption?>(null), CameraChoice().offered)
    }

    @Test
    fun aSwitchShowsTheNextCameraAndTheFirstAfterTheLast() {
        val cameras = listOf(back, front, webcam)
        assertEquals(front, CameraChoice(cameras, shown = back).next)
        assertEquals(back, CameraChoice(cameras, shown = webcam).next)
        assertEquals(back, CameraChoice(cameras).next)
        assertEquals(back, CameraChoice(cameras, shown = CameraOption("9", null, Facing.UNKNOWN)).next)
        assertNull(CameraChoice().next)
    }
}
