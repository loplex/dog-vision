package cz.loplex.dogvision.swing

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.speciesFacts
import cz.loplex.dogvision.desktop.LiveSession
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import cz.loplex.dogvision.texts.aboutKey
import cz.loplex.dogvision.texts.nameKey
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JSlider
import javax.swing.JTextArea
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * The simulation's controls in Swing, as ui's `Controls` lays them out for the Compose window: sections that open and
 * close, with the species, its facts, the simulation, the acuity and the view, then reset and the language. Each shows
 * what [session]'s state says and hands a change to [session]; it keeps only which sections are open.
 */
internal class Controls(private val session: LiveSession<*>) :
    Column(),
    Scrollable {
    /** What each control does when the state changes, in the order they were made. */
    private val updates = mutableListOf<(LiveSession.State) -> Unit>()

    /** Whether the controls are being set to the state, when what they report is not a change. */
    private var updating = false

    /** The texts shown last, which info buttons and lists word themselves in when they are used. */
    private var texts = session.state.value.texts

    init {
        border = BorderFactory.createEmptyBorder(
            UIScale.scale(8),
            UIScale.scale(16),
            UIScale.scale(8),
            UIScale.scale(16),
        )
        val start = session.state.value
        val params = start.view.params
        add(
            section(
                Str.SPECIES,
                startsOpen = true,
                speciesChoice(Str.SPECIES, Str.ABOUT_SPECIES, Species.entries, { it.view.params.species }) { species ->
                    if (species != null) setParams { copy(species = species) }
                },
            ),
        )
        add(section(Str.SELECTED_SPECIES, startsOpen = true, facts()))
        val chroma = ButtonGroup()
        add(
            section(
                Str.SIMULATION,
                startsOpen = true,
                slider(Str.ADAPTATION, Str.ABOUT_ADAPTATION, 0..100, { percent(it.view.params.adaptation) }) { value ->
                    setParams { copy(adaptation = value / 100.0) }
                },
                slider(Str.STRENGTH, Str.ABOUT_STRENGTH, 0..100, { percent(it.view.params.strength) }) { value ->
                    setParams { copy(strength = value / 100.0) }
                },
                label(Str.COLOUR_SATURATION, "semibold"),
                *ChromaScale.entries.map { scale ->
                    val fixed = scale == ChromaScale.FIXED
                    choice(
                        if (fixed) Str.CHROMA_FIXED else Str.CHROMA_RNL,
                        if (fixed) Str.ABOUT_CHROMA_FIXED else Str.ABOUT_CHROMA_RNL,
                        chroma,
                        { it.view.params.chromaScale == scale },
                    ) { setParams { copy(chromaScale = scale) } }
                }.toTypedArray(),
            ),
        )
        add(
            section(
                Str.ACUITY,
                startsOpen = params.acuity,
                check(Str.ACUITY_BLUR, Str.ABOUT_ACUITY_BLUR, { it.view.params.acuity }) { on ->
                    setParams { copy(acuity = on) }
                },
                slider(
                    Str.FIELD_OF_VIEW,
                    Str.ABOUT_FIELD_OF_VIEW,
                    10..120,
                    { it.view.params.fieldOfView.roundToInt() },
                    enabled = { it.view.params.acuity },
                ) { degrees -> setParams { copy(fieldOfView = degrees.toDouble()) } },
            ),
        )
        add(
            section(
                Str.VIEW,
                startsOpen = start.view.compare != null || start.view.difference,
                toggle(Str.SIDE_BY_SIDE, Str.ABOUT_SIDE_BY_SIDE, { it.view.sideBySide }) { on ->
                    session.changeView { it.copy(sideBySide = on) }
                },
                speciesChoice(
                    Str.COMPARE_WITH,
                    Str.ABOUT_COMPARE_WITH,
                    listOf(null) + Species.entries,
                    { it.view.compare },
                    enabled = { it.view.sideBySide },
                ) { species -> session.changeView { it.copy(compare = species) } },
                toggle(
                    Str.DIFFERENCE,
                    Str.ABOUT_DIFFERENCE,
                    { it.view.difference },
                    enabled = { it.view.sideBySide },
                ) { on -> session.changeView { it.copy(difference = on) } },
            ),
        )
        val reset = JButton().also { button ->
            button.addActionListener { session.reset() }
            on { button.text = it.texts.get(Str.RESET) }
        }
        add(
            Row(FlowLayout(FlowLayout.LEFT, 0, UIScale.scale(8))).apply {
                add(reset)
                add(infoButton({ it.get(Str.RESET) }, Str.ABOUT_RESET))
            },
        )
        add(languageChoice())
    }

    /** Sets every control to [state], words it in its language, and enables what it allows. */
    fun show(state: LiveSession.State) {
        texts = state.texts
        updating = true
        try {
            updates.forEach { it(state) }
        } finally {
            updating = false
        }
    }

    private fun on(update: (LiveSession.State) -> Unit) {
        updates += update
    }

    /** [action], unless the controls are being set to the state. */
    private fun reported(action: () -> Unit) {
        if (!updating) action()
    }

    private fun setParams(change: Params.() -> Params) = session.changeView { it.copy(params = it.params.change()) }

    private fun percent(share: Double) = (share * 100).roundToInt()

    /** A section: its title, which opens or closes it, and [content] under it while it is open. */
    private fun section(title: Str, startsOpen: Boolean, vararg content: JComponent): JComponent {
        val body = Column().apply {
            border = BorderFactory.createEmptyBorder(0, 0, UIScale.scale(8), 0)
            addAll(*content)
            isVisible = startsOpen
        }
        val chevron = JLabel()
        val name = JLabel().apply { putClientProperty(FlatClientProperties.STYLE_CLASS, "h4") }
        on { name.text = it.texts.get(title) }
        val header = JButton().apply {
            layout = BorderLayout()
            putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS)
            border = BorderFactory.createEmptyBorder(UIScale.scale(10), 0, UIScale.scale(10), 0)
            add(name, BorderLayout.CENTER)
            add(chevron, BorderLayout.EAST)
            addActionListener {
                body.isVisible = !body.isVisible
                chevron.icon = ShapeIcon(ShapeIcon.CHEVRON, if (body.isVisible) 180.0 else 0.0)
                revalidate()
            }
        }
        chevron.icon = ShapeIcon(ShapeIcon.CHEVRON, if (startsOpen) 180.0 else 0.0)
        return Column().apply { addAll(Row().apply { add(header) }, body, Row().apply { add(JSeparator()) }) }
    }

    /** A label worded by [text], in FlatLaf's style class [style] if one is given. */
    private fun label(text: Str, style: String? = null): JLabel = JLabel().also { label ->
        style?.let { label.putClientProperty(FlatClientProperties.STYLE_CLASS, it) }
        on { label.text = it.texts.get(text) }
    }

    /** A button that shows [about], what the control or fact [title] names means, in a dialog. */
    private fun infoButton(title: (Texts) -> String, about: Str): JButton = infoButton(title) { it.get(about) }

    private fun infoButton(title: (Texts) -> String, about: (Texts) -> String): JButton =
        // Worded when it is shown, not when the state changes, as the facts' buttons are made while it does.
        object : JButton(ShapeIcon(ShapeIcon.INFO)) {
            override fun getToolTipText() = texts.get(Str.ABOUT)
        }.apply {
            putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON)
            toolTipText = ""
            addActionListener { showAbout(this, title(texts), about(texts)) }
        }

    /** The dialog of an info button: [about] under [title], and a button that closes it. */
    private fun showAbout(parent: Component, title: String, about: String) {
        val text = JTextArea(about, 0, ABOUT_COLUMNS).apply {
            lineWrap = true
            wrapStyleWord = true
            isEditable = false
            isOpaque = false
            border = null
            transferHandler = null
            // Its wrapped height is known once it has its width: as wide as its columns, as high as that needs.
            setSize(preferredSize.width, Short.MAX_VALUE.toInt())
        }
        val scroll = JScrollPane(text).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            preferredSize = Dimension(text.preferredSize.width, minOf(text.preferredSize.height, UIScale.scale(360)))
        }
        val close = texts.get(Str.CLOSE)
        JOptionPane(scroll, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, arrayOf(close), close)
            .createDialog(SwingUtilities.getWindowAncestor(parent), title)
            .apply {
                isVisible = true
                dispose()
            }
    }

    /** A control or a fact on the left, and its info button on the right. */
    private fun withInfo(control: JComponent, title: (Texts) -> String, about: (Texts) -> String): JComponent =
        Row().apply {
            add(control, BorderLayout.CENTER)
            add(infoButton(title, about), BorderLayout.EAST)
        }

    /** A species out of [choices], null standing for the original image, under its [label]. */
    private fun speciesChoice(
        label: Str,
        about: Str,
        choices: List<Species?>,
        selected: (LiveSession.State) -> Species?,
        enabled: (LiveSession.State) -> Boolean = { true },
        onSelect: (Species?) -> Unit,
    ): JComponent {
        val box = JComboBox(choices.toTypedArray()).apply {
            renderer = worded { species -> (species as Species?)?.let(texts::speciesLabel) ?: texts.get(Str.ORIGINAL) }
            addActionListener { reported { onSelect(selectedItem as Species?) } }
        }
        val caption = label(label, "small")
        on { state ->
            if (box.selectedItem != selected(state)) box.selectedItem = selected(state)
            box.isEnabled = enabled(state)
            caption.isEnabled = enabled(state)
            box.repaint()
        }
        val column = Column().apply {
            border = BorderFactory.createEmptyBorder(UIScale.scale(4), 0, UIScale.scale(4), 0)
            addAll(Row().apply { add(caption) }, Row().apply { add(box) })
        }
        return withInfo(column, { it.get(label) }, { it.get(about) })
    }

    /** A list cell of a combo box, worded by [words] in the texts shown last. */
    private fun worded(words: (Any?) -> String) = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component = super.getListCellRendererComponent(list, words(value), index, isSelected, cellHasFocus)
    }

    /** A whole number in [range], shown beside [label], set by a slider under it. */
    private fun slider(
        label: Str,
        about: Str,
        range: IntRange,
        value: (LiveSession.State) -> Int,
        enabled: (LiveSession.State) -> Boolean = { true },
        onChange: (Int) -> Unit,
    ): JComponent {
        val slider = JSlider(range.first, range.last).apply {
            addChangeListener { reported { onChange(this.value) } }
        }
        val name = label(label, "semibold")
        val number = JLabel().apply { putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold") }
        on { state ->
            if (slider.value != value(state)) slider.value = value(state)
            number.text = value(state).toString()
            slider.isEnabled = enabled(state)
        }
        val top = Row().apply {
            add(name, BorderLayout.CENTER)
            add(
                Row(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    add(number)
                    add(infoButton({ it.get(label) }, about))
                },
                BorderLayout.EAST,
            )
        }
        return Column().apply {
            border = BorderFactory.createEmptyBorder(UIScale.scale(4), 0, UIScale.scale(4), 0)
            addAll(top, Row().apply { add(slider) })
        }
    }

    /** One of [group]'s choices, chosen where [selected] says. */
    private fun choice(
        text: Str,
        about: Str,
        group: ButtonGroup,
        selected: (LiveSession.State) -> Boolean,
        onClick: () -> Unit,
    ): JComponent {
        val button = JRadioButton().apply { addActionListener { reported(onClick) } }
        group.add(button)
        on { state ->
            button.text = state.texts.get(text)
            if (button.isSelected != selected(state)) button.isSelected = selected(state)
        }
        return withInfo(button, { it.get(text) }, { it.get(about) })
    }

    private fun check(
        text: Str,
        about: Str,
        checked: (LiveSession.State) -> Boolean,
        onChange: (Boolean) -> Unit,
    ): JComponent {
        val box = JCheckBox().apply { addActionListener { reported { onChange(isSelected) } } }
        on { state ->
            box.text = state.texts.get(text)
            box.isSelected = checked(state)
        }
        return withInfo(box, { it.get(text) }, { it.get(about) })
    }

    /** A switch after its [text], as Material's Switch in the Compose controls. */
    private fun toggle(
        text: Str,
        about: Str,
        checked: (LiveSession.State) -> Boolean,
        enabled: (LiveSession.State) -> Boolean = { true },
        onChange: (Boolean) -> Unit,
    ): JComponent {
        val switch = Switch().apply { addActionListener { reported { onChange(isSelected) } } }
        val name = JLabel().apply { labelFor = switch }
        on { state ->
            name.text = state.texts.get(text)
            switch.isSelected = checked(state)
            switch.isEnabled = enabled(state)
            name.isEnabled = enabled(state)
        }
        val control = Row().apply {
            border = BorderFactory.createEmptyBorder(UIScale.scale(4), 0, UIScale.scale(4), 0)
            add(name, BorderLayout.CENTER)
            add(switch, BorderLayout.EAST)
        }
        return withInfo(control, { it.get(text) }, { it.get(about) })
    }

    /**
     * What is known about the species, a row per fact, made again when the species or the language changes. A value
     * breaks only between its pieces, as in the Compose controls.
     */
    private fun facts(): JComponent {
        val rows = Column()
        var shown: Pair<Species, String>? = null
        on { state ->
            val species = state.view.params.species
            if (shown != species to state.texts.language) {
                shown = species to state.texts.language
                rows.removeAll()
                for (fact in speciesFacts(species, state.texts.facts)) {
                    val name = JLabel(state.texts.get(fact.label.nameKey)).apply {
                        putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold")
                        verticalAlignment = SwingConstants.TOP
                        border = BorderFactory.createEmptyBorder(UIScale.scale(4), 0, UIScale.scale(4), 0)
                        preferredSize = Dimension(UIScale.scale(FACT_LABEL_WIDTH), preferredSize.height)
                    }
                    val value = WrappedText().apply {
                        pieces = fact.value
                        border = BorderFactory.createEmptyBorder(UIScale.scale(4), 0, UIScale.scale(4), 0)
                    }
                    val row = Row().apply {
                        add(name, BorderLayout.WEST)
                        add(value, BorderLayout.CENTER)
                    }
                    rows.add(withInfo(row, { it.get(fact.label.nameKey) }, { it.get(fact.label.aboutKey) }))
                }
                rows.revalidate()
            }
        }
        return rows
    }

    /** The language: the system's, or one there are texts for, each named in itself. */
    private fun languageChoice(): JComponent {
        val box = JComboBox((listOf("") + Texts.LANGUAGES).toTypedArray()).apply {
            renderer = worded { tag ->
                if (tag == "") texts.get(Str.SYSTEM_LANGUAGE) else Texts.of(tag as String).get(Str.LANGUAGE_NAME)
            }
            addActionListener { reported { session.setLanguage(selectedItem as String) } }
        }
        on { state ->
            if (box.selectedItem != state.language) box.selectedItem = state.language
            box.repaint()
        }
        return Column().apply {
            border = BorderFactory.createEmptyBorder(UIScale.scale(8), 0, UIScale.scale(8), 0)
            addAll(Row().apply { add(label(Str.LANGUAGE, "small")) }, Row().apply { add(box) })
        }
    }

    // As wide as the scroll pane it is in, and scrolled a line at a time as a list is.
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = UIScale.scale(16)

    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) = visible.height

    override fun getScrollableTracksViewportWidth() = true

    override fun getScrollableTracksViewportHeight() = false

    private companion object {
        /** The width of a fact's name, as the Compose controls give it. */
        const val FACT_LABEL_WIDTH = 112

        /** How wide an info dialog's text is, in columns. */
        const val ABOUT_COLUMNS = 40
    }
}

/** The panel [controls] are laid out in, scrolled when they are taller than the window. */
internal fun scrolled(controls: Controls): JComponent = JScrollPane(controls).apply {
    border = null
    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    verticalScrollBar.unitIncrement = UIScale.scale(16)
}
