package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.CameraChoice
import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.Facing
import cz.loplex.dogvision.core.Mirroring
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.texts.Texts
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLLabelElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The camera section of the page's controls, built into a container of its own, as the page builds them. */
class ControlsTest {
    private val front = CameraOption("a1", "FaceTime HD Camera", Facing.FRONT)
    private val webcam = CameraOption("b2", null, Facing.UNKNOWN)

    /** In the document, since a click on a radio button out of it changes nothing a page is told of. */
    private val container = (document.createElement("div") as HTMLElement).also { document.body!!.appendChild(it) }
    private val chosen = mutableListOf<CameraOption?>()
    private val mirrorings = mutableListOf<Mirroring>()

    @AfterTest
    fun removeContainer() = container.remove()

    private fun controls(withCamera: Boolean = true) = Controls(
        container,
        Texts.of("en"),
        null,
        onChange = {},
        onCamera = if (withCamera) ({ chosen += it }) else null,
        onMirroring = { mirrorings += it },
        onLanguage = {},
    )

    private val cameraChoice get() = container.querySelector("#control-camera") as HTMLSelectElement?

    /** The radio button of the mirroring, and its label's text. */
    private fun mirroring(index: Int): Pair<HTMLInputElement, String> {
        val input = container.querySelectorAll("input[name=mirroring]").asList()[index] as HTMLInputElement
        return input to (input.parentElement as HTMLLabelElement).textContent.orEmpty().trim()
    }

    @Test
    fun theCamerasAreOfferedOffFirstAndAChoiceIsHandedOn() {
        val controls = controls()
        controls.show(View(), camera = CameraChoice(listOf(front, webcam), shown = front))
        val select = cameraChoice!!
        val names = select.options.asList().map { (it as HTMLOptionElement).textContent }
        assertEquals(listOf("Off", "FaceTime HD Camera", "Camera 2"), names)
        assertEquals("1", select.value)
        select.value = "2"
        select.dispatchEvent(Event("change"))
        select.value = "0"
        select.dispatchEvent(Event("change"))
        assertEquals(listOf(webcam, null), chosen)
    }

    @Test
    fun automaticSaysWhereTheCameraFacesAndIsDisabledWhereThatIsUnknown() {
        val controls = controls()
        controls.show(View(), camera = CameraChoice(listOf(front, webcam), shown = front))
        val (automatic, words) = mirroring(2)
        assertEquals("Automatic (front)", words)
        assertTrue(automatic.checked)
        assertFalse(automatic.disabled)
        controls.show(View(), camera = CameraChoice(listOf(front, webcam), shown = webcam))
        assertEquals("Automatic", mirroring(2).second)
        assertTrue(automatic.disabled)
        assertTrue(mirroring(0).first.checked, "Mirror")
        val (plain, _) = mirroring(1)
        plain.click()
        assertEquals(listOf(Mirroring.PLAIN), mirrorings)
    }

    @Test
    fun recordingLocksTheCamera() {
        controls().show(View(), recording = true, camera = CameraChoice(listOf(front), shown = front))
        assertTrue(cameraChoice!!.disabled)
        assertFalse(mirroring(0).first.disabled)
    }

    @Test
    fun aPageWithoutACameraHasNoCameraSection() {
        controls(withCamera = false).show(View())
        assertNull(cameraChoice)
    }
}
