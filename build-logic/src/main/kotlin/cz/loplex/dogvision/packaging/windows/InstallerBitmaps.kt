package cz.loplex.dogvision.packaging.windows

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Draws the two bitmaps of WiX's dialogs that an MSI shows in place of WiX's own, from [icon]:
 * [banner], WixUIBannerBmp, 493 by 58 pixels across the top of most dialogs,
 * white for the title WiX writes on its left, with the icon on its right;
 * and [dialog], WixUIDialogBmp, 493 by 312 behind the first dialog and the last,
 * whose left 164 pixels are a panel in the icon's background, [background]'s ic_launcher_background,
 * with the icon on it, and the rest white for WiX's text.
 */
abstract class InstallerBitmaps : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val icon: RegularFileProperty

    /** The Android app's colour resource that holds the launcher icon's background, which the icon is drawn on. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val background: RegularFileProperty

    @get:OutputFile
    abstract val banner: RegularFileProperty

    @get:OutputFile
    abstract val dialog: RegularFileProperty

    @TaskAction
    fun draw() {
        val source = ImageIO.read(icon.get().asFile)
        val panel = launcherBackground()
        bitmap(banner, 493, 58) {
            drawImage(source, 493 - 6 - 46, 6, 46, 46, null)
        }
        bitmap(dialog, 493, 312) {
            color = panel
            fillRect(0, 0, 164, 312)
            drawImage(source, (164 - 128) / 2, 48, 128, 128, null)
        }
    }

    private fun launcherBackground(): Color {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(background.get().asFile)
        val colors = document.getElementsByTagName("color")
        val value = (0 until colors.length).map { colors.item(it) }
            .single { it.attributes.getNamedItem("name")?.nodeValue == "ic_launcher_background" }
            .textContent.trim()
        check(Regex("#[0-9A-Fa-f]{6}").matches(value)) { "ic_launcher_background is $value, not #RRGGBB" }
        return Color(value.substring(1).toInt(16))
    }

    /** Writes a white bitmap of the size with [content] drawn on it, as BMP, which WiX takes, without alpha. */
    private fun bitmap(file: RegularFileProperty, width: Int, height: Int, content: Graphics2D.() -> Unit) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        graphics.content()
        graphics.dispose()
        check(ImageIO.write(image, "bmp", file.get().asFile)) { "ImageIO writes no BMP" }
    }
}
