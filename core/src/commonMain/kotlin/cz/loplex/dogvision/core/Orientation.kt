package cz.loplex.dogvision.core

/**
 * The rotation clockwise and the mirroring after it that an EXIF [orientation], 1 to 8 as TIFF numbers them, asks for;
 * any other number is upright. Android's `ExifInterface` names the same numbers.
 */
fun exifTurn(orientation: Int): Pair<Int, Boolean> = when (orientation) {
    // flipped horizontally
    2 -> 0 to true

    3 -> 180 to false

    // flipped vertically
    4 -> 180 to true

    // transposed
    5 -> 90 to true

    6 -> 90 to false

    // transversed
    7 -> 270 to true

    8 -> 270 to false

    else -> 0 to false
}
