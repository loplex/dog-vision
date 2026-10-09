package cz.loplex.dogvision.swing

import com.formdev.flatlaf.util.UIScale
import cz.loplex.dogvision.desktop.TopEdgeMenuBar
import cz.loplex.dogvision.texts.Menu
import cz.loplex.dogvision.texts.shownOf
import javax.swing.JFrame

/**
 * The menu bar of [frame], built again only when what its menus show changes, which would close a menu open,
 * and shown in the frame or laid over it as [TopEdgeMenuBar] has it.
 */
internal class MenuBarOf(frame: JFrame) : AutoCloseable {
    /** The menus shown last, which the window's keys press. */
    var menus = emptyList<Menu>()
        private set
    private var shown: List<Any>? = null
    private var bar = menuBar(emptyList())
    private val topEdge = TopEdgeMenuBar(frame, UIScale.scale(TopEdgeMenuBar.REVEAL_EDGE))

    /** Shows [menus], in the frame where [inFrame], else laid over it only as the pointer asks for them. */
    fun show(menus: List<Menu>, inFrame: Boolean) {
        this.menus = menus
        val now = shownOf(menus)
        if (now != shown) {
            shown = now
            bar = menuBar(menus)
        }
        topEdge.show(bar, inFrame)
    }

    override fun close() = topEdge.close()
}
