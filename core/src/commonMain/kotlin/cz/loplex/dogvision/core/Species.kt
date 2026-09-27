package cz.loplex.dogvision.core

/** Photopigment peak wavelengths of human cones in nm (Stockman & Sharpe 2000). */
object HumanCones {
    const val S = 420.7
    const val M = 530.3
    const val L = 558.9
}

/**
 * Where a value comes from: a citation, a note on how the value was derived from it, or both.
 * The notes are the parts a window translates; a citation stays as it is in every language.
 */
data class Source(val citation: String?, val note: Note? = null) {
    init {
        require(citation != null || note != null) { "A source needs a citation or a note" }
    }
}

/** A note after a citation, marking a value that is not simply measured. */
enum class Note(val english: String) {
    L_SHIFTED("L shifted 10 nm, see text"),
    M_SHIFTED("M shifted 10 nm, see text"),
    S_ASSUMED("S assumed"),
    RANGE_11_7_TO_14("11.7 to 14"),
    RANGE_1_8_TO_3_8("1.8 to 3.8"),
    ABOUT_60("about 60"),
    IN_AIR("in air"),
    STRIPES_8_2_ARCMIN("8.2 arcmin stripes"),
}

/** Share of S cones among all cones, as the lowest and highest reported across the retina. */
data class ConeShare(val low: Double, val high: Double, val source: String)

/**
 * Visual acuity in cycles per degree, resolving detail side by side and one above another. The two
 * differ only for cattle, whose pupil is a horizontal oval.
 */
data class Acuity(val across: Double, val up: Double, val source: Source)

enum class ColourVision(val coneTypes: Int) { MONOCHROMAT(1), DICHROMAT(2), TRICHROMAT(3) }

private val HUMAN_PEAKS = Source("Stockman & Sharpe 2000")
private val ARTIODACTYL_PEAKS = Source("Jacobs, Deegan & Neitz 1998")
private val ARTIODACTYL_SHARE = ConeShare(0.05, 0.10, "Schiviz et al. 2008")
private val HUMAN_SHARE = ConeShare(0.08, 0.12, "Curcio et al. 1991")
private val HUMAN_ACUITY = Acuity(72.0, 72.0, Source("Land & Nilsson 2012"))

/**
 * Every species simulated, in the order a window lists them.
 *
 * [peaks] are the cone peaks in nm: (S, M, L) for a trichromat, (S, L) for a dichromat, (L) for a
 * cone monochromat. A dichromat's longer cone is listed as L whatever its source calls it. Species
 * with an ultraviolet cone (mice, rats, birds) are left out: an RGB camera records nothing of what
 * that cone sees. [sCones] is null where no measurement was found, and the RNL scale then assumes
 * [ASSUMED_S_CONE_FRACTION]; [acuity] is null where none was found, and the species is left sharp.
 */
enum class Species(
    val id: String,
    val peaks: List<Double>,
    val peaksFrom: Source,
    val sCones: ConeShare? = null,
    val acuity: Acuity? = null,
) {
    DOG(
        "dog", listOf(429.0, 555.0), Source("Neitz, Geist & Jacobs 1989"),
        ConeShare(0.10, 0.18, "Mowat et al. 2008"), // area centralis, periphery
        Acuity(11.6, 11.6, Source("Odom et al. 1983")),
    ),
    CAT(
        "cat", listOf(450.0, 550.0), Source("Guenther & Zrenner 1993"),
        ConeShare(0.10, 0.20, "Linberg et al. 2001"),
        Acuity(10.0, 10.0, Source("Wässle 1971")),
    ),
    HORSE(
        "horse", listOf(428.0, 539.0), Source("Carroll et al. 2001"),
        ConeShare(0.10, 0.25, "Sandmann et al. 1996"),
        Acuity(23.3, 23.3, Source("Timney & Keil 1992")),
    ),
    COW(
        "cow", listOf(451.3, 555.3), ARTIODACTYL_PEAKS, ARTIODACTYL_SHARE,
        Acuity(2.6, 1.6, Source("Rehkämper et al. 2000")),
    ),
    SHEEP(
        "sheep", listOf(445.3, 552.2), ARTIODACTYL_PEAKS, ARTIODACTYL_SHARE,
        Acuity(12.85, 12.85, Source("Sumita et al. 2013", Note.RANGE_11_7_TO_14)),
    ),
    GOAT("goat", listOf(443.3, 552.5), ARTIODACTYL_PEAKS),
    PIG("pig", listOf(440.7, 556.7), ARTIODACTYL_PEAKS, ARTIODACTYL_SHARE),
    FALLOW_DEER("fallow-deer", listOf(453.6, 542.2), ARTIODACTYL_PEAKS),
    WHITE_TAILED_DEER("white-tailed-deer", listOf(456.0, 536.8), ARTIODACTYL_PEAKS),
    GUINEA_PIG("guinea-pig", listOf(429.0, 529.0), Source("Jacobs & Deegan 1994")),
    TREE_SQUIRREL(
        "tree-squirrel", listOf(444.0, 543.0), Source("Blakeslee, Jacobs & Neitz 1988"),
        acuity = Acuity(2.8, 2.8, Source("Jacobs, Birch & Blakeslee 1982", Note.RANGE_1_8_TO_3_8)),
    ),
    GROUND_SQUIRREL( // the California species
        "ground-squirrel", listOf(436.7, 518.9), Source("Jacobs, Neitz & Crognale 1985"),
        ConeShare(1 / 15.0, 1 / 15.0, "Kryger et al. 1998"), // 14 M cones per S cone
        Acuity(4.0, 4.0, Source("Jacobs et al. 1980")),
    ),
    FERRET(
        "ferret", listOf(430.0, 558.0), Source("Calderone & Jacobs 2003"),
        ConeShare(1 / 15.0, 1 / 15.0, "Calderone & Jacobs 2003"), // 14 L cones per S cone
    ),
    PROTANOPE("protanope", listOf(HumanCones.S, HumanCones.M), HUMAN_PEAKS, HUMAN_SHARE, HUMAN_ACUITY),
    DEUTERANOPE("deuteranope", listOf(HumanCones.S, HumanCones.L), HUMAN_PEAKS, HUMAN_SHARE, HUMAN_ACUITY),
    HUMAN("human", listOf(HumanCones.S, HumanCones.M, HumanCones.L), HUMAN_PEAKS, HUMAN_SHARE, HUMAN_ACUITY),

    // Anomalous trichromats of moderate severity: one cone shifted 10 nm towards the other, where
    // Machado, Oliveira & Fernandes (2009) take 20 nm as dichromacy.
    PROTANOMALOUS(
        "protanomalous", listOf(HumanCones.S, HumanCones.M, HumanCones.L - 10), Source(null, Note.L_SHIFTED),
        HUMAN_SHARE, HUMAN_ACUITY,
    ),
    DEUTERANOMALOUS(
        "deuteranomalous", listOf(HumanCones.S, HumanCones.M + 10, HumanCones.L), Source(null, Note.M_SHIFTED),
        HUMAN_SHARE, HUMAN_ACUITY,
    ),
    MACAQUE(
        "macaque", listOf(431.0, 536.0, 565.0), Source("Bowmaker et al. 1978, 1991"),
        acuity = Acuity(60.0, 60.0, Source("Nature Neuroscience 2024", Note.ABOUT_60)),
    ),
    HOWLER_MONKEY("howler-monkey", listOf(430.0, 530.0, 562.0), Source("Jacobs et al. 1996", Note.S_ASSUMED)),
    MARMOSET_FEMALE( // with the 543 and 563 nm M/L alleles
        "marmoset-female", listOf(423.0, 543.0, 563.0), Source("Travis 1988, Williams 1992"),
        acuity = Acuity(30.0, 30.0, Source("Troilo, Howland & Judge 1993")),
    ),
    HARBOUR_SEAL(
        "harbour-seal", listOf(510.0), Source("Crognale et al. 1998"),
        acuity = Acuity(5.5, 5.5, Source("Hanke & Dehnhardt 2009", Note.IN_AIR)),
    ),
    BOTTLENOSE_DOLPHIN( // the L opsin
        "bottlenose-dolphin", listOf(524.0), Source("Fasick et al. 1998"),
        acuity = Acuity(60 / (2 * 8.2), 60 / (2 * 8.2), Source("Herman et al. 1975", Note.STRIPES_8_2_ARCMIN)),
    ),
    ;

    val colourVision: ColourVision get() = ColourVision.entries.first { it.coneTypes == peaks.size }

    companion object {
        fun byId(id: String): Species? = entries.firstOrNull { it.id == id }
    }
}

/** The S-cone share assumed where none was measured: the middle of what the measured species span. */
const val ASSUMED_S_CONE_FRACTION = 0.10

/**
 * L cones per M cone for a trichromat. Humans with normal colour vision range from about twice as
 * many L as M cones to the reverse (Roorda & Williams 1999), so the RNL scale takes 1:1 for every
 * trichromat and calls it assumed.
 */
const val ASSUMED_L_TO_M = 1.0
