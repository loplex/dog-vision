package cz.loplex.dogvision.core

/** A time on the local clock, to the second, as the name of what is saved records it. */
data class ClockTime(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int)

/**
 * A name for a snapshot of [view] taken at [time], as the desktop window names it: dog-cat-20260925-105600.png,
 * with [suffix] before the [extension].
 *
 * A photo or a video converted at full size is named so too, with the suffix "-full", not after the
 * original as the desktop window names it: the system photo picker hides a file's name.
 */
fun snapshotName(view: View, time: ClockTime, suffix: String = "", extension: String = "png"): String {
    val shown = view.compare?.takeIf { view.sideBySide }?.let { "${it.id}-vs-${view.params.species.id}" }
        ?: view.params.species.id
    val stamp = with(time) {
        "$year" + pad(month) + pad(day) + "-" + pad(hour) + pad(minute) + pad(second)
    }
    return "dog-$shown-$stamp$suffix.$extension"
}

private fun pad(value: Int) = value.toString().padStart(2, '0')
