// Named after what it is for, saving's names, rather than after its one class.
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

private fun pad(value: Int) = value.toString().padStart(2, '0')
