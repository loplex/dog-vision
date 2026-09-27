package cz.loplex.dogvision.swing

import com.formdev.flatlaf.util.UIScale
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.Toolkit
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.math.max
import kotlin.math.min

/** A row as wide as the column it is in and as high as it needs: BoxLayout would otherwise stretch it. */
internal open class Row(layout: LayoutManager = BorderLayout(UIScale.scale(8), 0)) : JPanel(layout) {
    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/** Rows one under the other, each as wide as the column. */
internal open class Column : Row() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    /** Adds [components] under what it holds, and returns them. */
    fun addAll(vararg components: Component) = components.forEach { add(it) }
}

/**
 * Text broken into lines between its [pieces] alone, each kept whole, as the Compose controls keep a share with its
 * source's opening and a citation's authors together; its lines [centred] or from the left, and at most [maxWidth]
 * wide. Its height follows the width it is given.
 */
internal class WrappedText(private val centred: Boolean = false) : JComponent() {
    var pieces: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            revalidate()
            repaint()
        }

    var maxWidth = Int.MAX_VALUE
        set(value) {
            if (field == value) return
            field = value
            revalidate()
        }

    init {
        font = UIManager.getFont("Label.font")
        foreground = UIManager.getColor("Label.foreground")
    }

    override fun updateUI() {
        super.updateUI()
        font = UIManager.getFont("Label.font")
        foreground = UIManager.getColor("Label.foreground")
    }

    /** The lines [pieces] take in [width], each piece whole, and alone on its line where it is wider. */
    private fun lines(width: Int): List<String> {
        val metrics = getFontMetrics(font)
        val space = metrics.stringWidth(" ")
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var lineWidth = 0
        for (piece in pieces) {
            val pieceWidth = metrics.stringWidth(piece)
            if (line.isNotEmpty() && lineWidth + space + pieceWidth > width) {
                lines += line.toString()
                line.clear()
                lineWidth = 0
            }
            if (line.isNotEmpty()) {
                line.append(' ')
                lineWidth += space
            }
            line.append(piece)
            lineWidth += pieceWidth
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    /** The width the lines are broken in: what it is given once it is laid out, and at most [maxWidth]. */
    private fun textWidth(): Int {
        val whole = getFontMetrics(font).stringWidth(pieces.joinToString(" "))
        val given = if (width > 0) width - insets.left - insets.right else whole
        return max(1, min(min(given, whole), maxWidth))
    }

    override fun getPreferredSize(): Dimension {
        if (isPreferredSizeSet) return super.getPreferredSize()
        val width = min(getFontMetrics(font).stringWidth(pieces.joinToString(" ")), maxWidth)
        val height = lines(textWidth()).size * getFontMetrics(font).height
        return Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom)
    }

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val before = if (this.width > 0) lines(textWidth()).size else -1
        super.setBounds(x, y, width, height)
        // Its height is known only once its width is: another pass lays out what is under it, after this one, which
        // would mark it valid again.
        if (lines(textWidth()).size != before) SwingUtilities.invokeLater(::revalidate)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            antialiased(g2)
            g2.font = font
            g2.color = if (isEnabled) foreground else UIManager.getColor("Label.disabledForeground")
            val metrics = g2.fontMetrics
            val inner = width - insets.left - insets.right
            lines(textWidth()).forEachIndexed { i, line ->
                val x = insets.left + if (centred) (inner - metrics.stringWidth(line)) / 2 else 0
                g2.drawString(line, x, insets.top + i * metrics.height + metrics.ascent)
            }
        } finally {
            g2.dispose()
        }
    }
}

/** An on-off switch, as Material's Switch in the Compose controls, in the look and feel's accent colour. */
internal class Switch : JToggleButton() {
    init {
        isFocusPainted = false
        isBorderPainted = false
        isContentAreaFilled = false
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension =
        Dimension(UIScale.scale(TRACK_WIDTH + FOCUS_ROOM), UIScale.scale(TRACK_HEIGHT + FOCUS_ROOM))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            antialiased(g2)
            val trackWidth = UIScale.scale(TRACK_WIDTH)
            val trackHeight = UIScale.scale(TRACK_HEIGHT)
            val inset = UIScale.scale(THUMB_INSET)
            val x = (width - trackWidth) / 2
            val y = (height - trackHeight) / 2
            val accent = UIManager.getColor("Component.accentColor") ?: UIManager.getColor("Component.focusColor")
            val off = UIManager.getColor("Component.borderColor") ?: Color.GRAY
            if (!isEnabled) g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DISABLED_ALPHA)
            g2.color = if (isSelected) accent else off
            g2.fillRoundRect(x, y, trackWidth, trackHeight, trackHeight, trackHeight)
            val thumb = trackHeight - 2 * inset
            g2.color = Color.WHITE
            g2.fillOval(if (isSelected) x + trackWidth - inset - thumb else x + inset, y + inset, thumb, thumb)
            if (isFocusOwner) {
                g2.color = UIManager.getColor("Component.focusColor") ?: accent
                g2.stroke = BasicStroke(UIScale.scale(2f))
                g2.drawRoundRect(x - 1, y - 1, trackWidth + 1, trackHeight + 1, trackHeight + 2, trackHeight + 2)
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val TRACK_WIDTH = 36
        const val TRACK_HEIGHT = 20
        const val THUMB_INSET = 3
        const val DISABLED_ALPHA = 0.4f

        /** Around the track, where the focus ring is drawn. */
        const val FOCUS_ROOM = 4
    }
}

/**
 * A 24 x 24 icon of [shape], in its viewport of 24 x 24 as the Compose controls' icons are, turned [degrees] about its
 * centre, in the component's foreground colour.
 */
internal class ShapeIcon(private val shape: Shape, private val degrees: Double = 0.0) : Icon {
    override fun getIconWidth() = UIScale.scale(SIZE)

    override fun getIconHeight() = UIScale.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            antialiased(g2)
            g2.translate(x, y)
            g2.scale(iconWidth / SIZE.toDouble(), iconHeight / SIZE.toDouble())
            g2.rotate(Math.toRadians(degrees), SIZE / 2.0, SIZE / 2.0)
            val enabled = c?.isEnabled ?: true
            g2.color = if (enabled) {
                c?.foreground ?: UIManager.getColor("Label.foreground")
            } else {
                UIManager.getColor("Label.disabledForeground")
            }
            g2.fill(shape)
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val SIZE = 24

        /** A chevron pointing down, as ui's EXPAND_ICON: M16.59,8.59 L12,13.17 7.41,8.59 6,10 l6,6 6,-6z. */
        val CHEVRON: Shape = Path2D.Double().apply {
            moveTo(16.59, 8.59)
            lineTo(12.0, 13.17)
            lineTo(7.41, 8.59)
            lineTo(6.0, 10.0)
            lineTo(12.0, 16.0)
            lineTo(18.0, 10.0)
            closePath()
        }

        /** An i in a circle, as ui's INFO_ICON: a ring from 8 to 10 about the centre, a dot and a bar. */
        val INFO: Shape = Path2D.Double(Path2D.WIND_EVEN_ODD).apply {
            append(Ellipse2D.Double(2.0, 2.0, 20.0, 20.0), false)
            append(Ellipse2D.Double(4.0, 4.0, 16.0, 16.0), false)
            append(Rectangle2D.Double(11.0, 7.0, 2.0, 2.0), false)
            append(Rectangle2D.Double(11.0, 11.0, 2.0, 6.0), false)
        }
    }
}

/** Smooth edges, and text as the desktop smooths it, or smoothed where the desktop does not say. */
internal fun antialiased(g: Graphics2D) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    val desktop = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints") as? Map<*, *>
    if (desktop != null) {
        g.addRenderingHints(desktop)
    } else {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }
}
