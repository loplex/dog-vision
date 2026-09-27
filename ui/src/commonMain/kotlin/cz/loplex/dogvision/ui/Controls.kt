package cz.loplex.dogvision.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cz.loplex.dogvision.core.ChromaScale
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
 * The simulation's controls, in sections that open and close, as the desktop window has them. While
 * [recording], the controls that change how many images the view has are locked, as the desktop
 * locks them: a video cannot change its size.
 *
 * The language choice at the end shows [language], a language tag or "" for the system's, and hands a choice to
 * [onLanguage].
 */
@Composable
fun Controls(
    view: View,
    recording: Boolean,
    onChange: ((View) -> View) -> Unit,
    onReset: () -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val params = view.params
    fun setParams(change: Params.() -> Params) = onChange { it.copy(params = it.params.change()) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Section(Str.SPECIES, startsOpen = true) {
            SpeciesChoice(
                label = text(Str.SPECIES),
                about = Str.ABOUT_SPECIES,
                choices = Species.entries,
                selected = params.species,
                onSelect = { species -> setParams { copy(species = species!!) } },
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onReset, modifier = Modifier.padding(vertical = 8.dp)) {
                Text(text(Str.RESET))
            }
            InfoButton(text(Str.RESET), Str.ABOUT_RESET)
        }
        LanguageChoice(language, onLanguage)
    }
}

/**
 * The language: the system's, or one there are texts for, each named in itself as the desktop
 * window's Language menu names them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageChoice(current: String, onChoose: (String) -> Unit) {
    val languages = listOf("" to text(Str.SYSTEM_LANGUAGE)) +
        Texts.LANGUAGES.map { it to Texts.of(it).get(Str.LANGUAGE_NAME) }
    var expanded by rememberSaveable { mutableStateOf(false) }
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

/** A section: its title, which opens or closes it, and what it holds. */
@Composable
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
@OptIn(ExperimentalMaterial3Api::class)
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
    val name = { species: Species? -> species?.let(texts::speciesLabel) ?: original }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(
            expanded = expanded && enabled,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = name(selected),
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
                choices.forEach { species ->
                    DropdownMenuItem(
                        text = { Text(name(species)) },
                        onClick = {
                            onSelect(species)
                            expanded = false
                        },
                    )
                }
            }
        }
        InfoButton(label, about)
    }
}

/** A share from 0 to 1, shown and set in whole percent. */
@Composable
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
private fun Choice(text: String, about: Str, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, modifier = Modifier.padding(start = 8.dp).weight(1f))
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
            text = { Text(text(about), modifier = Modifier.verticalScroll(rememberScrollState())) },
        )
    }
}

/**
 * What is known about the species, a row per fact. A value breaks only between its pieces, so that
 * a share stays with its source's opening and a citation's authors stay together.
 */
@Composable
private fun Facts(species: Species) {
    val texts = LocalTexts.current
    val facts = remember(species, texts) { speciesFacts(species, texts.facts) }
    facts.forEach { fact ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text(fact.label.nameKey),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(112.dp),
            )
            Text(
                fact.value.joinToString(" ") { it.replace(' ', '\u00A0') },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
            )
            InfoButton(text(fact.label.nameKey), fact.label.aboutKey)
        }
    }
}
