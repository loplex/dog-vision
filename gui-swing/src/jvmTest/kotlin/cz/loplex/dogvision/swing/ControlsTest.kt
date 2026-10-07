package cz.loplex.dogvision.swing

import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.Facing
import cz.loplex.dogvision.core.Mirroring
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.desktop.Area
import cz.loplex.dogvision.desktop.Frame
import cz.loplex.dogvision.desktop.LiveSession
import cz.loplex.dogvision.desktop.Renderer
import cz.loplex.dogvision.desktop.Source
import cz.loplex.dogvision.desktop.WindowArguments
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JRadioButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Swing controls' camera section, over a session whose cameras, feeds and renderer are stand-ins,
 * set to the session's state as the window sets them whenever it changes.
 */
class ControlsTest {
    private var cameras = listOf(
        CameraOption("0", "Integrated Camera", Facing.UNKNOWN),
        CameraOption("2", null, Facing.BACK),
    )

    private val renderer = object : Renderer {
        override fun show(frame: Frame, live: Boolean, mirrored: Boolean) = Unit

        override fun setMirrored(mirrored: Boolean) = Unit

        override fun clear() = Unit

        override fun setView(view: View) = Unit

        override fun setArea(area: Area) = Unit

        override fun close() = Unit
    }

    private val session = LiveSession<Unit>(
        WindowArguments(),
        { _, _ -> renderer },
        { _, _, _ -> AutoCloseable {} },
        cameraLister = { cameras },
        inBackground = { it() },
        post = { it() },
        systemLanguages = { listOf("en") },
    )

    private val controls = Controls(session)

    /** Sets the controls to the session's state, as the window does on each change. */
    private fun shown() = controls.apply { show(session.state.value) }

    /** Every component in this one, at any depth. */
    private fun Container.descendants(): List<Component> =
        components.flatMap { listOf(it) + (it as? Container)?.descendants().orEmpty() }

    private inline fun <reified T : Component> Container.all(): List<T> = descendants().filterIsInstance<T>()

    @Test
    fun theCameraIsChosenFromOffAndTheCamerasEachNamed() {
        val box = shown().all<JComboBox<CameraOption?>>().first()
        assertEquals(listOf(null) + cameras, (0 until box.itemCount).map(box::getItemAt))
        assertEquals(cameras[0], box.selectedItem)
        val names = (0 until box.itemCount).map { index ->
            val cell = box.renderer.getListCellRendererComponent(JList(), box.getItemAt(index), index, false, false)
            (cell as JLabel).text
        }
        assertEquals(listOf("Off", "Integrated Camera", "Back camera"), names)
        box.selectedItem = cameras[1]
        assertEquals(cameras[1], session.state.value.camera.shown)
        box.selectedItem = null
        assertEquals(Source.None, session.state.value.source)
    }

    @Test
    fun theCamerasAreListedAgainAsTheListDropsDown() {
        val box = shown().all<JComboBox<CameraOption?>>().first()
        cameras = cameras.take(1)
        box.firePopupMenuWillBecomeVisible()
        shown()
        assertEquals(listOf(null) + cameras, (0 until box.itemCount).map(box::getItemAt))
    }

    @Test
    fun automaticIsDisabledWhereTheFacingIsUnknownAndSaysItElsewhere() {
        val buttons = shown().all<JRadioButton>()

        fun button(text: String) = buttons.single { it.text == text }
        assertFalse(button("Automatic").isEnabled)
        assertTrue(button("Mirror").isSelected)
        session.chooseCamera(cameras[1])
        shown()
        assertTrue(button("Automatic (back)").isEnabled)
        assertTrue(button("Automatic (back)").isSelected)
        button("Do not mirror").doClick()
        assertEquals(Mirroring.PLAIN, session.state.value.camera.mirroring)
    }
}
