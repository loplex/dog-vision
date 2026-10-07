// Named after what it is for, what is saved: its names and its picture, rather than after its one class.
@file:Suppress("MatchingDeclarationName")

package cz.loplex.dogvision.core

/** A time on the local clock, to the second, as the name of what is saved records it. */
data class ClockTime(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int)

/**
 * A name for a snapshot of [view] taken at [time]: dog-cat-20260925-105600.png, with [suffix] before the [extension].
 *
 * A photo or a video converted at full size is named so too, with the suffix "-full":
 * the system photo picker hides a file's name.
 */
fun snapshotName(view: View, time: ClockTime, suffix: String = "", extension: String = "png"): String {
    val stamp = with(time) {
        "$year" + pad(month) + pad(day) + "-" + pad(hour) + pad(minute) + pad(second)
    }
    return "dog-${shownName(view)}-$stamp$suffix.$extension"
}

/**
 * The species [view] shows, as the name of what is saved of it says them:
 * cat, or horse-vs-cat where it shows a horse beside a cat.
 */
fun shownName(view: View): String =
    view.compare?.takeIf { view.sideBySide }?.let { "${it.id}-vs-${view.params.species.id}" } ?: view.params.species.id

/** The images of a view put together as it shows them, side by side or one above another, as the app's stitch does. */
fun stitch(images: List<Image>, arrangement: Arrangement): Image {
    val width = images.first().width
    val height = images.first().height
    val whole = if (arrangement ==
        Arrangement.ROW
    ) {
        Image(width * images.size, height)
    } else {
        Image(width, height * images.size)
    }
    images.forEachIndexed { i, image ->
        val (x, y) = if (arrangement == Arrangement.ROW) width * i to 0 else 0 to height * i
        for (row in 0 until height) {
            image.pixels.copyInto(
                whole.pixels,
                (y + row) * whole.width + x,
                row * width,
                (row + 1) * width,
            )
        }
    }
    return whole
}

private fun pad(value: Int) = value.toString().padStart(2, '0')
