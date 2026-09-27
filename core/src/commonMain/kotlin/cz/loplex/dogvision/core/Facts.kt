package cz.loplex.dogvision.core

/** What a row of [speciesFacts] tells; a window names and describes each in its own language. */
enum class FactLabel { COLOUR_VISION, CONE_PEAKS, S_CONES, L_TO_M, NEUTRAL_POINT, RNL_SCALE, ACUITY }

/**
 * One row of what is known about a species. [value] is the pieces of information it holds, such as
 * a share and its source: joined by spaces they read as one line, and a window that has to break it
 * breaks it only between them.
 */
data class Fact(val label: FactLabel, val value: List<String>)

/** The wording of the facts in one language; a citation is left as it is in every language. */
interface FactTexts {
    val decimalSeparator: Char

    fun colourVision(kind: ColourVision): String

    /** "dichromat, 2 cone types" from the kind's name and the number of cone types. */
    fun coneTypes(kind: String, count: Int): String

    /** "10%" from "10". */
    fun percent(value: String): String

    /** "8%–12%" from "8" and "12". */
    fun percentRange(low: String, high: String): String

    /** "10%–18% of cones" from the share. */
    fun shareOfCones(share: String): String

    val assumed: String
    val noColourAxis: String
    val seesOnlyGrey: String

    /** "x0.79 of fixed", or "x1.02 and x0.61 of fixed", from the factors. */
    fun rnlOfFixed(gains: List<String>): String

    val notFoundMeasured: String
    val leftSharp: String

    /** "11.6 c/deg" from the value. */
    fun acuity(value: String): String

    /** "2.6 c/deg side by side," from the value. */
    fun acuityAcross(value: String): String

    /** "1.6 one above another" from the value. */
    fun acuityUp(value: String): String

    fun note(note: Note): String
}

/**
 * A number as Python's format(value, "g") gives it with [digits] significant digits: 420.7, 429, 3.66.
 * As Python does, it rounds the double's exact value, and a tie to the even digit.
 */
expect fun formatSignificant(value: Double, digits: Int, decimalSeparator: Char): String

/** A number with [decimals] digits after the point, as Python's format(value, ".2f"), rounded as formatSignificant. */
expect fun formatFixed(value: Double, decimals: Int, decimalSeparator: Char): String

/** A share, or a range of shares when the two differ in whole percent. */
fun percent(low: Double, high: Double, texts: FactTexts): String {
    val lowText = formatFixed(low * 100, 0, texts.decimalSeparator)
    val highText = formatFixed(high * 100, 0, texts.decimalSeparator)
    return if (lowText == highText) texts.percent(lowText) else texts.percentRange(lowText, highText)
}

private val COMMA = Regex(", ")
private val DIGIT = Regex("\\d")

/**
 * [text] split after each ", " that follows a number, the comma kept with what it ends.
 *
 * In a citation the commas before the year separate authors, and the ones after it a note or
 * another citation, as in "Jacobs, Birch & Blakeslee 1982, 1.8 to 3.8".
 */
fun pieces(text: String): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    for (comma in COMMA.findAll(text)) {
        if (DIGIT.containsMatchIn(text.substring(start, comma.range.first))) {
            parts += text.substring(start, comma.range.first + 1)
            start = comma.range.last + 1
        }
    }
    return parts + text.substring(start)
}

/** A source in brackets, its note translated, split into [pieces]. */
fun sourcePieces(source: Source, texts: FactTexts): List<String> =
    pieces("(" + listOfNotNull(source.citation, source.note?.let(texts::note)).joinToString(", ") + ")")

/** What the simulation knows about a species, as rows for a table. */
fun speciesFacts(species: Species, texts: FactTexts): List<Fact> {
    val decimal = texts.decimalSeparator
    val peaks = species.peaks
    val n = peaks.size
    val names = when (n) {
        3 -> "SML"
        2 -> "SL"
        else -> "L"
    }
    val cones = peaks.mapIndexed { i, peak -> "${names[i]} ${formatSignificant(peak, 6, decimal)} nm" }
    val facts = mutableListOf(
        Fact(FactLabel.COLOUR_VISION, listOf(texts.coneTypes(texts.colourVision(species.colourVision), n))),
        Fact(
            FactLabel.CONE_PEAKS,
            cones.dropLast(1).map { "$it," } + cones.last() + sourcePieces(species.peaksFrom, texts),
        ),
    )
    if (n == 1) {
        facts += Fact(FactLabel.RNL_SCALE, listOf(texts.noColourAxis, texts.seesOnlyGrey))
    } else {
        val share = species.sCones
        val shareText = if (share == null) {
            percent(ASSUMED_S_CONE_FRACTION, ASSUMED_S_CONE_FRACTION, texts)
        } else {
            percent(share.low, share.high, texts)
        }
        facts += Fact(
            FactLabel.S_CONES,
            listOf(texts.shareOfCones(shareText)) + pieces("(${share?.source ?: texts.assumed})"),
        )
        facts += if (n == 3) {
            Fact(FactLabel.L_TO_M, listOf("${formatSignificant(ASSUMED_L_TO_M, 6, decimal)} : 1", "(${texts.assumed})"))
        } else {
            Fact(FactLabel.NEUTRAL_POINT, listOf("${formatFixed(neutralPoint(species), 0, decimal)} nm", "(model)"))
        }
        val gains = rnlGains(species).map { "x" + formatFixed(it, 2, decimal) }
        facts += Fact(FactLabel.RNL_SCALE, listOf(texts.rnlOfFixed(gains)))
    }
    facts += Fact(FactLabel.ACUITY, acuityValue(species, texts))
    return facts
}

/** The species' acuity and its source, as [speciesFacts] gives a value. */
fun acuityValue(species: Species, texts: FactTexts): List<String> {
    val acuity = species.acuity ?: return listOf(texts.notFoundMeasured, texts.leftSharp)
    val across = formatSignificant(acuity.across, 3, texts.decimalSeparator)
    val up = formatSignificant(acuity.up, 3, texts.decimalSeparator)
    val value = if (across == up) {
        listOf(texts.acuity(across))
    } else {
        listOf(texts.acuityAcross(across), texts.acuityUp(up))
    }
    return value + sourcePieces(acuity.source, texts)
}
