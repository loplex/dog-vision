package cz.loplex.dogvision.cli

import cz.loplex.dogvision.common.readPhoto
import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.blue
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.core.green
import cz.loplex.dogvision.core.meanLinearRgb
import cz.loplex.dogvision.core.red
import cz.loplex.dogvision.core.rgb
import cz.loplex.dogvision.texts.Texts
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/*
 * The documents' figures in docs/images, rendered by core:
 *
 * - apples-species.png: the photo shiny-red-apples.jpg as it is and as five species see it;
 * - apples-acuity.png: a sticker on an apple, as sharp as a human and as a dog resolve it;
 * - apples-difference.png: a deuteranope's view, a dog's, and the map of where they differ;
 * - species-grid.png: a hue sweep and eight colour patches as every species sees them, with both saturation scales.
 *
 * ./gradlew :cli:renderFigures writes them, and FiguresTest fails when one is not what core renders.
 *
 * The photo is "Shiny red apples" by Leon Brooks, released into the public domain:
 * https://commons.wikimedia.org/wiki/File:Shiny_red_apples.jpg
 */

/** An image of a figure, its top left corner at [x] and [y]. */
class Placed(val image: Image, val x: Int, val y: Int)

/** A text of a figure, its baseline starting at [x] and [y]. */
class Label(val text: String, val x: Int, val y: Int)

/** Images and texts on white, [width] x [height]. */
class Figure(val width: Int, val height: Int, val images: List<Placed>, val labels: List<Label>) {
    /**
     * The figure as one image.\
     * Only the images are compared by FiguresTest: fonts differ between systems.
     */
    fun draw(): BufferedImage {
        val figure = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = figure.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        for (placed in images) {
            val image = placed.image
            figure.setRGB(placed.x, placed.y, image.width, image.height, image.pixels, 0, image.width)
        }
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
        graphics.color = Color.BLACK
        for (label in labels) graphics.drawString(label.text, label.x, label.y)
        graphics.dispose()
        return figure
    }
}

/** White space between the images of a figure. */
private const val GAP = 8

/** The height of the white strip under an image that holds its caption. */
private const val CAPTION_HEIGHT = 28

/** One image of a figure, with the caption under it saying what it shows. */
private class Cell(val image: Image, val caption: String)

/** [rows] of cells, all of one size, [GAP] pixels apart, each image above its caption. */
private fun captioned(rows: List<List<Cell>>): Figure {
    val cellWidth = rows[0][0].image.width
    val cellHeight = rows[0][0].image.height
    require(rows.flatten().all { it.image.width == cellWidth && it.image.height == cellHeight }) {
        "Every image of a figure has to be $cellWidth x $cellHeight"
    }
    val columns = rows.maxOf { it.size }
    val images = mutableListOf<Placed>()
    val labels = mutableListOf<Label>()
    for ((r, row) in rows.withIndex()) {
        for ((c, cell) in row.withIndex()) {
            val x = c * (cellWidth + GAP)
            val y = r * (cellHeight + CAPTION_HEIGHT + GAP)
            images += Placed(cell.image, x, y)
            labels += Label(cell.caption, x + 6, y + cellHeight + 19)
        }
    }
    return Figure(
        columns * cellWidth + (columns - 1) * GAP,
        rows.size * (cellHeight + CAPTION_HEIGHT) + (rows.size - 1) * GAP,
        images,
        labels,
    )
}

/** The photo is averaged in 8 x 8 blocks, which gives cells of 320 x 240. */
private const val SHRINK = 8
private val SPECIES_SHOWN =
    listOf(Species.DOG, Species.CAT, Species.PROTANOPE, Species.DEUTERANOPE, Species.HARBOUR_SEAL)

/** The degrees the whole photo is taken to span, for the acuity figure. */
private const val FIELD_OF_VIEW = 30.0

/** The columns and the rows of the full photo around a sticker. */
private val STICKER_COLUMNS = 0 until 320
private val STICKER_ROWS = 440 until 680

private val texts = Texts.of("en")

/** [image] averaged in [SHRINK] x [SHRINK] blocks, which, unlike a resampling filter, leaves no choice to a library. */
fun shrink(image: Image): Image {
    val small = Image(image.width / SHRINK, image.height / SHRINK)
    val count = SHRINK * SHRINK
    for (y in 0 until small.height) {
        for (x in 0 until small.width) {
            var r = 0
            var g = 0
            var b = 0
            for (dy in 0 until SHRINK) {
                for (dx in 0 until SHRINK) {
                    val pixel = image[x * SHRINK + dx, y * SHRINK + dy]
                    r += red(pixel)
                    g += green(pixel)
                    b += blue(pixel)
                }
            }
            val mean = { sum: Int -> (sum + count / 2) / count }
            small.pixels[y * small.width + x] = rgb(mean(r), mean(g), mean(b))
        }
    }
    return small
}

/** [view] of [photo] as the command line converts it, and the share of pixels that differ if it has the map. */
private fun rendered(photo: Image, view: View): Pair<Image, Double?> {
    val (width, height) = composedSize(view, photo.width, photo.height)
    val out = Image(width, height)
    return out to compose(photo, view, out, { meanLinearRgb(photo) })
}

/** The part of [image] in [columns] and [rows]. */
private fun crop(image: Image, columns: IntRange, rows: IntRange): Image {
    val part = Image(columns.count(), rows.count())
    for (y in rows) {
        for (x in columns) part.pixels[(y - rows.first) * part.width + x - columns.first] = image[x, y]
    }
    return part
}

private fun speciesFigure(photo: Image): Figure {
    val small = shrink(photo)
    val cells = listOf(Cell(small, texts.captions(View(), null).first())) +
        SPECIES_SHOWN.map { species ->
            Cell(rendered(small, View(Params(species), sideBySide = false)).first, texts.speciesLabel(species))
        }
    return captioned(cells.chunked(3))
}

/** The blur is set by the whole photo's width, so it is applied to the whole photo and then cropped. */
private fun acuityFigure(photo: Image): Figure {
    val cells = listOf(Cell(crop(photo, STICKER_COLUMNS, STICKER_ROWS), texts.captions(View(), null).first())) +
        listOf(Species.HUMAN, Species.DOG).map { species ->
            val params = Params(species, acuity = true, fieldOfView = FIELD_OF_VIEW)
            val blurred = rendered(photo, View(params, sideBySide = false)).first
            Cell(crop(blurred, STICKER_COLUMNS, STICKER_ROWS), "${texts.speciesLabel(species)}, 30° across")
        }
    return captioned(listOf(cells))
}

private fun differenceFigure(photo: Image): Figure {
    val small = shrink(photo)
    val view = View(Params(Species.DOG), compare = Species.DEUTERANOPE, difference = true)
    val (composed, share) = rendered(small, view)
    val columns = { image: Int -> small.width * image until small.width * (image + 1) }
    val cells = texts.captions(view, share).mapIndexed { image, caption ->
        Cell(crop(composed, columns(image), 0 until small.height), caption)
    }
    return captioned(listOf(cells))
}

private const val STRIP_WIDTH = 400
private const val LABEL_WIDTH = 280
private const val SWEEP_HEIGHT = 40
private const val PATCH_HEIGHT = 28
private val PATCHES = listOf(
    rgb(230, 40, 40),
    rgb(240, 140, 20),
    rgb(240, 220, 40),
    rgb(60, 180, 60),
    rgb(40, 190, 200),
    rgb(40, 80, 220),
    rgb(140, 60, 200),
    rgb(220, 80, 170),
)

/** A saturated hue sweep from red to magenta, over 300 of the 360 degrees, above eight patches. */
private fun testChart(): Image {
    val chart = Image(STRIP_WIDTH, SWEEP_HEIGHT + PATCH_HEIGHT)
    for (x in 0 until STRIP_WIDTH) {
        val hue = Color.HSBtoRGB(x * 300f / (STRIP_WIDTH - 1) / 360f, 1f, 1f)
        val patch = PATCHES[x * PATCHES.size / STRIP_WIDTH]
        for (y in 0 until chart.height) chart.pixels[y * STRIP_WIDTH + x] = if (y < SWEEP_HEIGHT) hue else patch
    }
    return chart
}

/** The test chart as every species sees it, with both colour saturation scales, one species a row. */
private fun speciesGrid(): Figure {
    val chart = testChart()
    val header = 24
    val rowHeight = chart.height + 4
    val fixedX = LABEL_WIDTH
    val rnlX = LABEL_WIDTH + STRIP_WIDTH + GAP
    val images = mutableListOf<Placed>()
    val labels = mutableListOf(Label("fixed", fixedX + 6, 17), Label("rnl", rnlX + 6, 17))
    val rows = listOf(null) + Species.entries
    for ((i, species) in rows.withIndex()) {
        val y = header + i * rowHeight
        val (fixed, rnl) = if (species == null) {
            chart to chart
        } else {
            ChromaScale.entries.map { scale ->
                rendered(chart, View(Params(species, chromaScale = scale), sideBySide = false)).first
            }.let { it[0] to it[1] }
        }
        images += Placed(fixed, fixedX, y)
        images += Placed(rnl, rnlX, y)
        val name = species?.let(texts::speciesLabel) ?: texts.captions(View(), null).first()
        labels += Label(name, 6, y + chart.height / 2 + 5)
    }
    return Figure(rnlX + STRIP_WIDTH, header + rows.size * rowHeight - 4, images, labels)
}

/** Each figure's file name, and how it is rendered from the photo; the grid is rendered from a chart of its own. */
val FIGURES: Map<String, (Image) -> Figure> = mapOf(
    "apples-species.png" to ::speciesFigure,
    "apples-acuity.png" to ::acuityFigure,
    "apples-difference.png" to ::differenceFigure,
    "species-grid.png" to { _ -> speciesGrid() },
)

/** The photo the figures are rendered from, in [images]. */
fun applePhoto(images: File): Image = checkNotNull(readPhoto(File(images, "shiny-red-apples.jpg"))) {
    "No photo in $images"
}

/** Writes every figure to the directory given as the only argument, docs/images. */
fun main(args: Array<String>) {
    val images = File(requireNotNull(args.singleOrNull()) { "Give the directory of the figures, docs/images" })
    val photo = applePhoto(images)
    for ((name, figure) in FIGURES) {
        val file = File(images, name)
        check(ImageIO.write(figure(photo).draw(), "png", file)) { "No PNG writer" }
        println("Wrote $file")
    }
}
