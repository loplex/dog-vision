package cz.loplex.dogvision.swing

import com.formdev.flatlaf.util.UIScale
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
@Suppress("MagicNumber")
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

    /** Adds [components] under what it holds. */
    fun addAll(vararg components: Component) = components.forEach { add(it) }
}

/**
 * Text broken into lines between its [pieces], each kept whole where it fits on a line,
 * so that a share stays with its source's opening and a citation's authors together,
 * and a piece wider than the line on lines of its own,
 * broken at its spaces, as the Compose controls and the web page break one;
 * its lines [centred] or from the left, and at most [maxWidth] wide.\
 * Its height follows the width it is given.\
 * A piece that is [link] is drawn as a link, which [onLink] is given when it is clicked.
 */
internal class WrappedText(private val centred: Boolean = false) : JComponent() {
    var pieces: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            revalidate()
            repaint()
        }

    var link: String? = null
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    var onLink: (String) -> Unit = {}

    var maxWidth = Int.MAX_VALUE
        set(value) {
            if (field == value) return
            field = value
            revalidate()
        }

    init {
        lookAsLabel()
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                val link = link ?: return
                if (linkArea()?.contains(event.point) == true) onLink(link)
            }

            override fun mouseMoved(event: MouseEvent) {
                val overLink = linkArea()?.contains(event.point) == true
                cursor = Cursor.getPredefinedCursor(if (overLink) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    override fun updateUI() {
        super.updateUI()
        lookAsLabel()
    }

    private fun lookAsLabel() {
        font = UIManager.getFont("Label.font")
        foreground = UIManager.getColor("Label.foreground")
        // Measured with the smoothing it is drawn with, which a component without a UI delegate is not given:
        // text smoothed is wider than text that is not, and a line measured without it would run past the edge.
        putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING, UIManager.get(RenderingHints.KEY_TEXT_ANTIALIASING))
    }

    /**
     * The lines [pieces] take in [width]: each piece whole where it fits on a line,
     * and one wider than [width] on lines of its own, broken at its spaces, with what follows it on the next line.
     */
    internal fun lines(width: Int): List<String> {
        val metrics = getFontMetrics(font)
        val space = metrics.stringWidth(" ")
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var lineWidth = 0
        fun breakLine() {
            if (line.isEmpty()) return
            lines += line.toString()
            line.clear()
            lineWidth = 0
        }
        fun add(text: String, textWidth: Int) {
            if (line.isNotEmpty() && lineWidth + space + textWidth > width) breakLine()
            if (line.isNotEmpty()) {
                line.append(' ')
                lineWidth += space
            }
            line.append(text)
            lineWidth += textWidth
        }
        for (piece in pieces) {
            val pieceWidth = metrics.stringWidth(piece)
            if (pieceWidth <= width) {
                add(piece, pieceWidth)
            } else {
                breakLine()
                for (word in piece.split(' ')) add(word, metrics.stringWidth(word))
                breakLine()
            }
        }
        breakLine()
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
        // Its height is known only once its width is:
        // another pass lays out what is under it, after this one, which would mark it valid again.
        if (lines(textWidth()).size != before) SwingUtilities.invokeLater(::revalidate)
    }

    /** Where [line] starts, on the left. */
    private fun lineX(line: String): Int {
        val inner = width - insets.left - insets.right
        return insets.left + if (centred) (inner - getFontMetrics(font).stringWidth(line)) / 2 else 0
    }

    /** Where in [line] [link] starts as a piece of its own, or -1 where it is none of its pieces. */
    private fun linkIn(line: String): Int {
        val link = link ?: return -1
        val at = line.indexOf(link)
        val end = at + link.length
        val whole = at >= 0 && (at == 0 || line[at - 1] == ' ') && (end == line.length || line[end] == ' ')
        return if (whole) at else -1
    }

    /** Where [link] is drawn, on the first line it is a piece of, or null where it is none. */
    @Suppress("ReturnCount")
    private fun linkArea(): Rectangle? {
        val link = link ?: return null
        val metrics = getFontMetrics(font)
        lines(textWidth()).forEachIndexed { i, line ->
            val at = linkIn(line)
            if (at >= 0) {
                val x = lineX(line) + metrics.stringWidth(line.substring(0, at))
                return Rectangle(x, insets.top + i * metrics.height, metrics.stringWidth(link), metrics.height)
            }
        }
        return null
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            antialiased(g2)
            g2.font = font
            val text = if (isEnabled) foreground else UIManager.getColor("Label.disabledForeground")
            val metrics = g2.fontMetrics
            lines(textWidth()).forEachIndexed { i, line ->
                val x = lineX(line)
                val y = insets.top + i * metrics.height + metrics.ascent
                val at = linkIn(line)
                g2.color = text
                if (at < 0) {
                    g2.drawString(line, x, y)
                    return@forEachIndexed
                }
                val link = line.substring(at, at + checkNotNull(link).length)
                val before = line.substring(0, at)
                val linkX = x + metrics.stringWidth(before)
                g2.drawString(before, x, y)
                g2.drawString(line.substring(at + link.length), linkX + metrics.stringWidth(link), y)
                g2.color = UIManager.getColor("Component.linkColor") ?: text
                g2.drawString(link, linkX, y)
                g2.fillRect(linkX, y + 1, metrics.stringWidth(link), max(1, UIScale.scale(1)))
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
 * A 24 x 24 icon of [shape], in its viewport of 24 x 24 as the Compose controls' icons are,
 * turned [degrees] about its centre, in the component's foreground colour.
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

        /** Three dots one above another, as ui's MORE_ICON: a menu of everything the window does. */
        val MORE: Shape = Path2D.Double().apply {
            append(Ellipse2D.Double(10.0, 4.0, 4.0, 4.0), false)
            append(Ellipse2D.Double(10.0, 10.0, 4.0, 4.0), false)
            append(Ellipse2D.Double(10.0, 16.0, 4.0, 4.0), false)
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

/**
 * Smooth edges, and text as the look and feel smooths it,
 * which [smoothTextAtEverySize] may have changed from the desktop's;
 * as the desktop smooths it where the look and feel does not say, and smoothed where neither says.
 */
internal fun antialiased(g: Graphics2D) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    val desktop = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints") as? Map<*, *>
    if (desktop != null) {
        g.addRenderingHints(desktop)
    } else {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }
    val lookAndFeel = UIManager.get(RenderingHints.KEY_TEXT_ANTIALIASING)
    if (lookAndFeel != null) g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, lookAndFeel)
}
