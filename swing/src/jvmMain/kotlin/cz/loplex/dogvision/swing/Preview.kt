package cz.loplex.dogvision.swing

import com.formdev.flatlaf.util.UIScale
import cz.loplex.dogvision.desktop.Area
import cz.loplex.dogvision.desktop.LiveSession
import cz.loplex.dogvision.desktop.Picture
import cz.loplex.dogvision.texts.Str
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.UIManager
import kotlin.math.roundToInt

/**
 * The images laid out as the picture shown last has them, with their captions, or why there are none, and where the
 * state offers it an offer to install ffmpeg. The images are drawn at the screen's own pixels, not at Swing's scaled
 * ones, so that they are as sharp on a HiDPI screen as in the Compose window, and [session] is told the area in them.
 */
internal class Preview(private val session: LiveSession<BufferedImage>) : JPanel(GridBagLayout()) {
    /** The picture to draw, the latest the session has. */
    var picture: Picture<BufferedImage>? = null
        set(value) {
            field = value
            repaint()
        }

    private var state = session.state.value
    private var area: Area? = null

    private val message = WrappedText(centred = true)
    private val install = JButton().apply { addActionListener { session.installFfmpeg() } }
    private val installFailure = WrappedText(centred = true)
    private val failure = Column().apply {
        val padding = UIScale.scale(24)
        border = BorderFactory.createEmptyBorder(padding, padding, padding, padding)
        for (part in listOf(message, install, installFailure)) part.alignmentX = Component.CENTER_ALIGNMENT
        addAll(message, Box.createVerticalStrut(UIScale.scale(16)), install, installFailure)
    }

    init {
        add(failure)
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) = sendArea()
            },
        )
        // The screen's scale is known once the panel is on one, and changes when the window moves to another.
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) sendArea()
        }
        addPropertyChangeListener("graphicsConfiguration") { sendArea() }
    }

    /** Shows why nothing is shown, if something is why, as [state] has it. */
    fun show(state: LiveSession.State) {
        this.state = state
        val failure = state.failure
        this.failure.isVisible = failure != null
        message.pieces = failure?.words(state.texts)?.split(' ').orEmpty()
        install.isVisible = state.offersFfmpeg
        install.isEnabled = !state.installingFfmpeg
        install.text = state.texts.get(if (state.installingFfmpeg) Str.INSTALLING_FFMPEG else Str.INSTALL_FFMPEG)
        installFailure.isVisible = state.offersFfmpeg && state.ffmpegFailure != null
        installFailure.pieces = state.ffmpegFailure?.words(state.texts)?.split(' ').orEmpty()
        repaint()
    }

    /** How many of the screen's pixels a pixel of Swing's is across. */
    private fun screenScale(): Double = graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0

    /** Tells the session the area the images are laid out on, in the screen's pixels, when it changes. */
    private fun sendArea() {
        if (width <= 0 || height <= 0) return
        val scale = screenScale()
        val maxMessageWidth = width - UIScale.scale(48)
        message.maxWidth = maxMessageWidth
        installFailure.maxWidth = maxMessageWidth
        val area = Area(
            (width * scale).roundToInt(),
            (height * scale).roundToInt(),
            (UIScale.scale(CAPTION_HEIGHT) * scale).roundToInt(),
            (UIScale.scale(GAP) * scale).roundToInt(),
        )
        if (area == this.area) return
        this.area = area
        session.setArea(area)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val picture = picture ?: return
        if (state.failure != null) return
        val g2 = g.create() as Graphics2D
        try {
            // The image one to one onto the screen's pixels, from where the panel starts on it.
            val scaled = g2.transform
            g2.transform = AffineTransform.getTranslateInstance(
                scaled.translateX.roundToInt().toDouble(),
                scaled.translateY.roundToInt().toDouble(),
            )
            g2.drawImage(picture.image, 0, 0, null)
            g2.transform = scaled
            captions(g2, picture, scaled.scaleX, scaled.scaleY)
        } finally {
            g2.dispose()
        }
    }

    /** What each image shows, under it, centred on one line and cut short with an ellipsis where it is too long. */
    private fun captions(g: Graphics2D, picture: Picture<BufferedImage>, scaleX: Double, scaleY: Double) {
        antialiased(g)
        g.font = UIManager.getFont("Label.font")
        g.color = UIManager.getColor("Label.foreground")
        val metrics = g.fontMetrics
        val captions = state.texts.captions(picture.view, picture.differenceShare)
        for ((box, caption) in picture.layout.captions.zip(captions)) {
            val left = box.left / scaleX
            val width = box.width / scaleX
            val top = box.top / scaleY
            val height = box.height / scaleY
            var text = caption
            while (text.length > 1 && metrics.stringWidth(text) > width) text = text.dropLast(2) + "…"
            val x = left + (width - metrics.stringWidth(text)) / 2
            val y = top + (height - metrics.height) / 2 + metrics.ascent
            g.drawString(text, x.toFloat(), y.toFloat())
        }
    }

    private companion object {
        /** The height of a caption under each image, and the gap between images, as in the Compose window. */
        const val CAPTION_HEIGHT = 40
        const val GAP = 6
    }
}
