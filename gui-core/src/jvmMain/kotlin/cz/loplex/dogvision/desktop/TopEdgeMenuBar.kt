package cz.loplex.dogvision.desktop

import java.awt.AWTEvent
import java.awt.Component
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import javax.swing.JFrame
import javax.swing.JLayeredPane
import javax.swing.JMenuBar
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.ChangeListener

/**
 * The menu bar of [frame], which both windows show the same way, the Compose window's bar being Swing's too:
 * in the frame, or, where it is hidden, laid over the top of the window
 * once the pointer has rested at the window's top edge, [edge] high, for a moment, or past it on the title bar,
 * and taken away once the pointer leaves it with no menu of it open, as a full-screen browser shows its toolbar.
 *
 * Laid over the Compose window, the bar is a Swing component over skiko's canvas, a heavyweight one,
 * which AWT cuts the bar's shape out of; its menus are windows of their own there.
 */
class TopEdgeMenuBar(private val frame: JFrame, private val edge: Int) : AutoCloseable {
    private var bar = JMenuBar()
    private var inFrame = true
    private var laidOver = false

    private val dwell = Timer(REVEAL_DELAY) { layOver() }.apply { isRepeats = false }
    private val pointer = AWTEventListener { event -> (event as? MouseEvent)?.let(::pointerMoved) }
    private val menuClosed = ChangeListener {
        if (laidOver && !menuOpen() && !pointerOverBar()) takeAway()
    }
    private val resized = object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent) {
            if (laidOver) placeOverlay()
        }
    }

    init {
        Toolkit.getDefaultToolkit()
            .addAWTEventListener(pointer, AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK)
        MenuSelectionManager.defaultManager().addChangeListener(menuClosed)
        frame.layeredPane.addComponentListener(resized)
    }

    /** Shows [bar] in the frame where [inFrame], else laid over it only as the pointer asks for it. */
    fun show(bar: JMenuBar, inFrame: Boolean) {
        if (bar === this.bar && inFrame == this.inFrame) return
        if (laidOver && bar !== this.bar) frame.layeredPane.remove(this.bar)
        this.bar = bar
        this.inFrame = inFrame
        if (inFrame) {
            dwell.stop()
            if (laidOver) takeAway()
            frame.jMenuBar = bar
        } else {
            frame.jMenuBar = null
            if (laidOver) placeOverlay()
        }
        // Laid out and painted at once, so that the content, a heavyweight canvas in the Compose window,
        // does not show the bar's room unpainted for a frame.
        frame.rootPane.validate()
        frame.rootPane.paintImmediately(frame.rootPane.bounds)
    }

    override fun close() {
        dwell.stop()
        Toolkit.getDefaultToolkit().removeAWTEventListener(pointer)
        MenuSelectionManager.defaultManager().removeChangeListener(menuClosed)
        frame.layeredPane.removeComponentListener(resized)
    }

    /** Lays the bar over the top of the window, or keeps it there, or takes it away, as the pointer at [event] asks. */
    private fun pointerMoved(event: MouseEvent) {
        val source = event.source as? Component ?: return
        if (inFrame || !inThisWindow(source)) return
        val at = SwingUtilities.convertPoint(source, event.point, frame.layeredPane)
        val left = event.id == MouseEvent.MOUSE_EXITED && !frame.layeredPane.contains(at)
        val atEdge = atEdge(at, left)
        when {
            laidOver -> if (leftBar(at, left, atEdge) && !menuOpen()) takeAway()
            atEdge -> if (!dwell.isRunning) dwell.start()
            else -> dwell.stop()
        }
    }

    /** Whether [source] is the frame, or a component in it. */
    private fun inThisWindow(source: Component) = source === frame || SwingUtilities.getWindowAncestor(source) === frame

    /**
     * Whether the pointer at [at], a point of the layered pane,
     * rests at the window's top edge, [left] whether it has just left the window.\
     * A pointer that leaves through the top edge, onto the title bar, still rests at it:
     * it overshoots the edge more often than it stops on it.\
     * Java gives the point it left at past the edge, or, under XWayland, on it.
     */
    private fun atEdge(at: Point, left: Boolean): Boolean {
        val top = at.y - frame.contentPane.y
        return top < edge && (top >= 0 || left) && at.x in 0 until frame.layeredPane.width
    }

    /** Whether the pointer at [at] has left the bar laid over: out of the window but not at its edge, or below it. */
    private fun leftBar(at: Point, left: Boolean, atEdge: Boolean) =
        (left && !atEdge) || at.y - frame.contentPane.y > bar.height

    private fun layOver() {
        if (inFrame || laidOver) return
        laidOver = true
        placeOverlay()
    }

    /**
     * Puts the bar over the content, in a layer above it, so that Swing paints it again over what repaints under it:
     * an Int as add's second argument would be the bar's place in the content's layer instead, whose repaints cover it.
     */
    private fun placeOverlay() {
        if (bar.parent !== frame.layeredPane) {
            frame.layeredPane.setLayer(bar, JLayeredPane.PALETTE_LAYER)
            frame.layeredPane.add(bar)
        }
        bar.setBounds(0, frame.contentPane.y, frame.layeredPane.width, bar.preferredSize.height)
        bar.revalidate()
        frame.layeredPane.repaint()
    }

    private fun takeAway() {
        laidOver = false
        frame.layeredPane.remove(bar)
        frame.layeredPane.repaint()
    }

    /** Whether a menu of the bar, or any other, is open, which keeps the bar laid over until it closes. */
    private fun menuOpen() = MenuSelectionManager.defaultManager().selectedPath.isNotEmpty()

    private fun pointerOverBar(): Boolean {
        val screen = MouseInfo.getPointerInfo()?.location ?: return false
        val at = Point(screen).also { SwingUtilities.convertPointFromScreen(it, bar) }
        return bar.isShowing && bar.contains(at)
    }

    companion object {
        /** How high the window's top edge is that the pointer rests at, before the window's scale. */
        const val REVEAL_EDGE = 6

        /** How long the pointer rests at the edge before the bar is laid over, in ms. */
        private const val REVEAL_DELAY = 300
    }
}
