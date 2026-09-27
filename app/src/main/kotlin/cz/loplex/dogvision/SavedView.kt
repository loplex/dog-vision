package cz.loplex.dogvision

import android.os.Bundle
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View

/**
 * The controls of [View] as a Bundle, which Android keeps while it ends the app in the background.
 * The arrangement is left out: the screen's layout sets it anew.
 */
internal fun View.toBundle(): Bundle = Bundle().apply {
    putString(SPECIES, params.species.name)
    putDouble(ADAPTATION, params.adaptation)
    putDouble(STRENGTH, params.strength)
    putString(CHROMA_SCALE, params.chromaScale.name)
    putBoolean(ACUITY, params.acuity)
    putDouble(FIELD_OF_VIEW, params.fieldOfView)
    putBoolean(SIDE_BY_SIDE, sideBySide)
    putString(COMPARE, compare?.name)
    putBoolean(DIFFERENCE, difference)
}

/** The view whose controls [toBundle] kept; a control it does not hold, or no longer knows, is at its default. */
internal fun Bundle.toView(): View {
    val view = View()
    val params = view.params
    return View(
        params = Params(
            species = enumNamed(getString(SPECIES)) ?: params.species,
            adaptation = getDouble(ADAPTATION, params.adaptation),
            strength = getDouble(STRENGTH, params.strength),
            chromaScale = enumNamed(getString(CHROMA_SCALE)) ?: params.chromaScale,
            acuity = getBoolean(ACUITY, params.acuity),
            fieldOfView = getDouble(FIELD_OF_VIEW, params.fieldOfView),
        ),
        sideBySide = getBoolean(SIDE_BY_SIDE, view.sideBySide),
        compare = enumNamed<Species>(getString(COMPARE)) ?: view.compare,
        difference = getBoolean(DIFFERENCE, view.difference),
    )
}

private inline fun <reified T : Enum<T>> enumNamed(name: String?): T? = enumValues<T>().find { it.name == name }

private const val SPECIES = "species"
private const val ADAPTATION = "adaptation"
private const val STRENGTH = "strength"
private const val CHROMA_SCALE = "chromaScale"
private const val ACUITY = "acuity"
private const val FIELD_OF_VIEW = "fieldOfView"
private const val SIDE_BY_SIDE = "sideBySide"
private const val COMPARE = "compare"
private const val DIFFERENCE = "difference"
