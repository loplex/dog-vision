package cz.loplex.dogvision.texts

import cz.loplex.dogvision.core.ColourVision
import cz.loplex.dogvision.core.FactLabel
import cz.loplex.dogvision.core.Note
import cz.loplex.dogvision.core.Species

// The string of each species, kind of colour vision, fact and note: each a when, so that one added without a string
// does not compile.

/** The species' name. */
val Species.nameKey: Str
    get() = when (this) {
        Species.DOG -> Str.SPECIES_DOG
        Species.CAT -> Str.SPECIES_CAT
        Species.HORSE -> Str.SPECIES_HORSE
        Species.COW -> Str.SPECIES_COW
        Species.SHEEP -> Str.SPECIES_SHEEP
        Species.GOAT -> Str.SPECIES_GOAT
        Species.PIG -> Str.SPECIES_PIG
        Species.FALLOW_DEER -> Str.SPECIES_FALLOW_DEER
        Species.WHITE_TAILED_DEER -> Str.SPECIES_WHITE_TAILED_DEER
        Species.GUINEA_PIG -> Str.SPECIES_GUINEA_PIG
        Species.TREE_SQUIRREL -> Str.SPECIES_TREE_SQUIRREL
        Species.GROUND_SQUIRREL -> Str.SPECIES_GROUND_SQUIRREL
        Species.FERRET -> Str.SPECIES_FERRET
        Species.PROTANOPE -> Str.SPECIES_PROTANOPE
        Species.DEUTERANOPE -> Str.SPECIES_DEUTERANOPE
        Species.HUMAN -> Str.SPECIES_HUMAN
        Species.PROTANOMALOUS -> Str.SPECIES_PROTANOMALOUS
        Species.DEUTERANOMALOUS -> Str.SPECIES_DEUTERANOMALOUS
        Species.MACAQUE -> Str.SPECIES_MACAQUE
        Species.HOWLER_MONKEY -> Str.SPECIES_HOWLER_MONKEY
        Species.MARMOSET_FEMALE -> Str.SPECIES_MARMOSET_FEMALE
        Species.HARBOUR_SEAL -> Str.SPECIES_HARBOUR_SEAL
        Species.BOTTLENOSE_DOLPHIN -> Str.SPECIES_BOTTLENOSE_DOLPHIN
    }

/** The kind of colour vision's name. */
val ColourVision.nameKey: Str
    get() = when (this) {
        ColourVision.MONOCHROMAT -> Str.MONOCHROMAT
        ColourVision.DICHROMAT -> Str.DICHROMAT
        ColourVision.TRICHROMAT -> Str.TRICHROMAT
    }

/** The fact's label. */
val FactLabel.nameKey: Str
    get() = when (this) {
        FactLabel.COLOUR_VISION -> Str.FACT_COLOUR_VISION
        FactLabel.CONE_PEAKS -> Str.FACT_CONE_PEAKS
        FactLabel.S_CONES -> Str.FACT_S_CONES
        FactLabel.L_TO_M -> Str.FACT_L_TO_M
        FactLabel.NEUTRAL_POINT -> Str.FACT_NEUTRAL_POINT
        FactLabel.RNL_SCALE -> Str.FACT_RNL_SCALE
        FactLabel.ACUITY -> Str.FACT_ACUITY
    }

/** What the fact means, for a reader who does not know the model. */
val FactLabel.aboutKey: Str
    get() = when (this) {
        FactLabel.COLOUR_VISION -> Str.ABOUT_FACT_COLOUR_VISION
        FactLabel.CONE_PEAKS -> Str.ABOUT_FACT_CONE_PEAKS
        FactLabel.S_CONES -> Str.ABOUT_FACT_S_CONES
        FactLabel.L_TO_M -> Str.ABOUT_FACT_L_TO_M
        FactLabel.NEUTRAL_POINT -> Str.ABOUT_FACT_NEUTRAL_POINT
        FactLabel.RNL_SCALE -> Str.ABOUT_FACT_RNL_SCALE
        FactLabel.ACUITY -> Str.ABOUT_FACT_ACUITY
    }

/** The note, worded. */
val Note.key: Str
    get() = when (this) {
        Note.L_SHIFTED -> Str.NOTE_L_SHIFTED
        Note.M_SHIFTED -> Str.NOTE_M_SHIFTED
        Note.S_ASSUMED -> Str.NOTE_S_ASSUMED
        Note.RANGE_11_7_TO_14 -> Str.NOTE_RANGE_11_7_TO_14
        Note.RANGE_1_8_TO_3_8 -> Str.NOTE_RANGE_1_8_TO_3_8
        Note.ABOUT_60 -> Str.NOTE_ABOUT_60
        Note.IN_AIR -> Str.NOTE_IN_AIR
        Note.STRIPES_8_2_ARCMIN -> Str.NOTE_STRIPES_8_2_ARCMIN
    }
