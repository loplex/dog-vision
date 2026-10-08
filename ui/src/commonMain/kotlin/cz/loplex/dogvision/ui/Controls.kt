@file:Suppress("TooManyFunctions")

package cz.loplex.dogvision.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import cz.loplex.dogvision.core.CameraChoice
import cz.loplex.dogvision.core.CameraOption
import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Mirroring
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.speciesFacts
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import cz.loplex.dogvision.texts.aboutKey
import cz.loplex.dogvision.texts.nameKey
import kotlin.math.roundToInt

/**
 * The simulation's controls, in sections that open and close.\
 * While [recording], the controls that change how many images the view has are locked:
 * a video cannot change its size; so is the choice of camera, as the source's size would change.
 *
 * The camera section at the top shows [camera], and hands a camera chosen, or null for Off, to [onCamera],
 * and a mirroring chosen to [onMirroring]; [onCamerasOpened] is called as its list drops down.\
 * The language choice at the end shows [language], a language tag or "" for the system's,
 * and hands a choice to [onLanguage].\
 * Where [info] is given, a closed section after the view shows what it words for the parameters:
 * the model and the checks it passes, as the desktop's command line prints them.
 */
@Composable
fun Controls(
    view: View,
    recording: Boolean,
    onChange: ((View) -> View) -> Unit,
    onReset: () -> Unit,
    camera: CameraChoice,
    onCamera: (CameraOption?) -> Unit,
    onMirroring: (Mirroring) -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCamerasOpened: () -> Unit = {},
    info: ((Params, Texts) -> String)? = null,
) {
    val params = view.params
    fun setParams(change: Params.() -> Params) = onChange { it.copy(params = it.params.change()) }
    ScrollingColumn(modifier, PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        Section(Str.CAMERA, startsOpen = false) {
            CameraSection(camera, enabled = !recording, onCamera, onMirroring, onCamerasOpened)
        }
        Section(Str.SPECIES, startsOpen = true) {
            SpeciesChoice(
                label = text(Str.SPECIES),
                about = Str.ABOUT_SPECIES,
                choices = Species.entries,
                selected = params.species,
                onSelect = { species -> species?.let { setParams { copy(species = it) } } },
            )
        }
        Section(Str.SELECTED_SPECIES, startsOpen = true) {
            Facts(params.species)
        }
        Section(Str.SIMULATION, startsOpen = true) {
            PercentSlider(Str.ADAPTATION, Str.ABOUT_ADAPTATION, params.adaptation) { value ->
                setParams { copy(adaptation = value) }
            }
            PercentSlider(Str.STRENGTH, Str.ABOUT_STRENGTH, params.strength) { value ->
                setParams { copy(strength = value) }
            }
            Text(text(Str.COLOUR_SATURATION), style = MaterialTheme.typography.labelLarge)
            ChromaScale.entries.forEach { scale ->
                val fixed = scale == ChromaScale.FIXED
                Choice(
                    text = text(if (fixed) Str.CHROMA_FIXED else Str.CHROMA_RNL),
                    about = if (fixed) Str.ABOUT_CHROMA_FIXED else Str.ABOUT_CHROMA_RNL,
                    selected = params.chromaScale == scale,
                    onClick = { setParams { copy(chromaScale = scale) } },
                )
            }
        }
        Section(Str.ACUITY, startsOpen = params.acuity) {
            Check(text(Str.ACUITY_BLUR), Str.ABOUT_ACUITY_BLUR, params.acuity) { on ->
                setParams { copy(acuity = on) }
            }
            LabelledSlider(
                label = text(Str.FIELD_OF_VIEW),
                about = Str.ABOUT_FIELD_OF_VIEW,
                value = params.fieldOfView.roundToInt(),
                range = 10..120,
                enabled = params.acuity,
            ) { degrees -> setParams { copy(fieldOfView = degrees.toDouble()) } }
        }
        Section(Str.VIEW, startsOpen = view.compare != null || view.difference) {
            Toggle(
                text(Str.SIDE_BY_SIDE),
                Str.ABOUT_SIDE_BY_SIDE,
                view.sideBySide,
                enabled = !recording,
            ) { on -> onChange { it.copy(sideBySide = on) } }
            SpeciesChoice(
                label = text(Str.COMPARE_WITH),
                about = Str.ABOUT_COMPARE_WITH,
                choices = listOf(null) + Species.entries,
                selected = view.compare,
                enabled = view.sideBySide,
                onSelect = { species -> onChange { it.copy(compare = species) } },
            )
            Toggle(
                text(Str.DIFFERENCE),
                Str.ABOUT_DIFFERENCE,
                view.difference,
                enabled = view.sideBySide && !recording,
            ) { on -> onChange { it.copy(difference = on) } }
        }
        if (info != null) {
            Section(Str.INFO_SECTION, startsOpen = false) {
                ModelInfo(params, info)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onReset, modifier = Modifier.padding(vertical = 8.dp)) {
                Text(text(Str.RESET))
            }
            InfoButton(text(Str.RESET), Str.ABOUT_RESET)
        }
        LanguageChoice(language, onLanguage)
    }
}

/** The language: the system's, or one there are texts for, each named in itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageChoice(current: String, onChoose: (String) -> Unit) {
    val languages = listOf("" to text(Str.SYSTEM_LANGUAGE)) +
        Texts.LANGUAGES.map { it to Texts.of(it).get(Str.LANGUAGE_NAME) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    CountedWhileOpen(expanded)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = languages.firstOrNull { it.first == current }?.second ?: languages.first().second,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(text(Str.LANGUAGE)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languages.forEach { (tag, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onChoose(tag)
                    },
                )
            }
        }
    }
}

/**
 * The camera [choice] offers, Off first, which a choice hands to [onCamera] while [enabled],
 * and the mirroring of its image, which a choice hands to [onMirroring]:
 * Automatic, worded with where the camera faces, only where that is known.\
 * [onOpened] is called as the list of cameras drops down.
 */
@Composable
private fun CameraSection(
    choice: CameraChoice,
    enabled: Boolean,
    onCamera: (CameraOption?) -> Unit,
    onMirroring: (Mirroring) -> Unit,
    onOpened: () -> Unit,
) {
    val texts = LocalTexts.current
    val offered = choice.offered
    ListChoice(
        label = text(Str.CAMERA),
        about = Str.ABOUT_CAMERA,
        names = texts.cameraNames(choice),
        selected = offered.indexOf(choice.shown),
        enabled = enabled,
        onSelect = { onCamera(offered[it]) },
        onOpened = onOpened,
    )
    Text(text(Str.MIRRORING), style = MaterialTheme.typography.labelLarge)
    val choices = listOf(
        Triple(Mirroring.MIRROR, text(Str.MIRROR), Str.ABOUT_MIRROR),
        Triple(Mirroring.PLAIN, text(Str.DO_NOT_MIRROR), Str.ABOUT_DO_NOT_MIRROR),
        Triple(Mirroring.AUTO, texts.automaticMirroring(choice.facing), Str.ABOUT_MIRROR_AUTOMATIC),
    )
    choices.forEach { (mirroring, name, about) ->
        Choice(
            text = name,
            about = about,
            selected = choice.shownMirroring == mirroring,
            enabled = mirroring != Mirroring.AUTO || choice.automaticAvailable,
            onClick = { onMirroring(mirroring) },
        )
    }
}

/** A section: its title, which opens or closes it, and what it holds. */
@Composable
@Suppress("MagicNumber")
private fun Section(title: Str, startsOpen: Boolean, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(startsOpen) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                EXPAND_ICON,
                contentDescription = null,
                modifier = Modifier.rotate(if (open) 180f else 0f),
            )
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(bottom = 8.dp)) { content() }
        }
        HorizontalDivider()
    }
}

/** A species out of [choices], null standing for the original image. */
@Composable
private fun SpeciesChoice(
    label: String,
    about: Str,
    choices: List<Species?>,
    selected: Species?,
    onSelect: (Species?) -> Unit,
    enabled: Boolean = true,
) {
    val texts = LocalTexts.current
    val original = text(Str.ORIGINAL)
    ListChoice(
        label = label,
        about = about,
        names = choices.map { species -> species?.let(texts::speciesLabel) ?: original },
        selected = choices.indexOf(selected),
        enabled = enabled,
        onSelect = { onSelect(choices[it]) },
    )
}

/**
 * One of [names], the [selected]th shown, in a list that drops down under its [label];
 * [onSelect] takes the index, and [onOpened] is called as the list drops down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListChoice(
    label: String,
    about: Str,
    names: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onOpened: () -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    CountedWhileOpen(expanded && enabled)
    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(
            expanded = expanded && enabled,
            onExpandedChange = { open ->
                if (open && !expanded) onOpened()
                expanded = open
            },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = names.getOrElse(selected) { "" },
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                singleLine = true,
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
            )
            ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                names.forEachIndexed { index, name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelect(index)
                            expanded = false
                        },
                    )
                }
            }
        }
        InfoButton(label, about)
    }
}

/**
 * How many of the controls' lists are dropped down: the Compose window leaves its keys to a list while one is,
 * as a list's keys reach the window whether or not the list takes them.
 */
class OpenLists {
    var count by mutableIntStateOf(0)
        private set

    internal fun opened() = count++

    internal fun closed() = count--
}

/** The lists the controls count in, none but the Compose window's reads. */
val LocalOpenLists = staticCompositionLocalOf { OpenLists() }

/** Counts a list in [LocalOpenLists] for as long as it is [open]. */
@Composable
private fun CountedWhileOpen(open: Boolean) {
    val lists = LocalOpenLists.current
    DisposableEffect(open) {
        if (open) lists.opened()
        onDispose { if (open) lists.closed() }
    }
}

/** A share from 0 to 1, shown and set in whole percent. */
@Composable
@Suppress("MagicNumber")
private fun PercentSlider(label: Str, about: Str, value: Double, onChange: (Double) -> Unit) =
    LabelledSlider(text(label), about, (value * 100).roundToInt(), 0..100) { onChange(it / 100.0) }

@Composable
private fun LabelledSlider(
    label: String,
    about: Str,
    value: Int,
    range: IntRange,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(value.toString(), style = MaterialTheme.typography.labelLarge)
            InfoButton(label, about)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
        )
    }
}

@Composable
private fun Choice(text: String, about: Str, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, enabled = enabled, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        val disabled = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        Text(
            text,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
            color = if (enabled) Color.Unspecified else disabled,
        )
        InfoButton(text, about)
    }
}

@Composable
private fun Check(text: String, about: Str, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, onValueChange = onChange, role = Role.Checkbox)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text, modifier = Modifier.padding(start = 8.dp).weight(1f))
        InfoButton(text, about)
    }
}

@Composable
private fun Toggle(text: String, about: Str, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, enabled = enabled, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        InfoButton(text, about)
    }
}

/** An info button that shows [about], what the control or fact called [title] means. */
@Composable
fun InfoButton(title: String, about: Str) {
    var shown by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { shown = true }) {
        Icon(INFO_ICON, contentDescription = text(Str.ABOUT))
    }
    if (shown) {
        AlertDialog(
            onDismissRequest = { shown = false },
            confirmButton = { TextButton(onClick = { shown = false }) { Text(text(Str.CLOSE)) } },
            title = { Text(title) },
            text = { ScrollingColumn { Text(text(about)) } },
        )
    }
}

/**
 * What is known about the species, a row per fact.\
 * A value breaks between its pieces,
 * so that a share stays with its source's opening and a citation's authors stay together;
 * a piece wider than the column on its own breaks at its spaces, as the web page's pieces do.
 */
@Composable
private fun Facts(species: Species) {
    val texts = LocalTexts.current
    val facts = remember(species, texts) { speciesFacts(species, texts.facts) }
    val style = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val space = remember(measurer, style, density) { with(density) { measurer.measure(" ", style).size.width.toDp() } }
    facts.forEach { fact ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text(fact.label.nameKey),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(112.dp),
            )
            // Laid out in pieces, read out as one text.
            FlowRow(
                Modifier.weight(1f).padding(vertical = 4.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(space),
            ) {
                fact.value.forEach { Text(it, style = style) }
            }
            InfoButton(text(fact.label.nameKey), fact.label.aboutKey)
        }
    }
}

/** What [info] words for [params], in a fixed-width font, so that a matrix's columns line up; it can be selected. */
@Composable
private fun ModelInfo(params: Params, info: (Params, Texts) -> String) {
    val texts = LocalTexts.current
    val words = remember(params, texts) { info(params, texts) }
    SelectionContainer {
        Text(words, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

/** How opaque a disabled control's text is, as Material 3 dims a disabled control's content. */
private const val DISABLED_ALPHA = 0.38f
