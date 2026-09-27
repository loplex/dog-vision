package cz.loplex.dogvision.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A chevron pointing down: a section that opens. */
internal val EXPAND_ICON = icon("M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z")

/** An i in a circle: what a control or a fact means. */
internal val INFO_ICON = icon(
    "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 " +
        "-8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z",
)

/** A 24 dp icon of one path in a 24 x 24 viewport, as the app's vector drawables are, tinted where it is shown. */
private fun icon(path: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.White))
        .build()
