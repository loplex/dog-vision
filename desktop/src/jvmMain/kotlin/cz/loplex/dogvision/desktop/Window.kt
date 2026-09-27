package cz.loplex.dogvision.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cz.loplex.dogvision.cli.Arguments
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import cz.loplex.dogvision.ui.Controls
import cz.loplex.dogvision.ui.LocalTexts
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import org.jetbrains.skia.Image as SkiaImage

/** The height of a caption under each image, and the gap between images, as in the Android app. */
private val CAPTION_HEIGHT = 40.dp
private val GAP = 6.dp

/**
 * Opens the window on what [arguments] ask for: the file given with --window, a photo or else a video, or the camera
 * --camera names; returns once the window is closed. Another file is opened from the system's dialog, from the o key
 * as in the Python program's window, or dropped onto the window; q or Escape closes it, as in the Python program's.
 * What it shows is a [LiveSession]'s, which the window only lays out.
 */
fun showWindow(arguments: Arguments): Int {
    application(exitProcessOnExit = false) {
        val session = remember { LiveSession.drawnOnGpu(arguments, ::composeImage) }
        DisposableEffect(session) {
            onDispose { session.close() }
        }
        val state by session.state.collectAsState()
        val dialogs = remember { Dialogs() }
        val open = { dialogs.open(state.texts, state.source)?.let(session::openFile) }
        CompositionLocalProvider(LocalTexts provides state.texts) {
            Window(
                onCloseRequest = ::exitApplication,
                title = state.texts.get(Str.APP_NAME),
                state = rememberWindowState(width = 1280.dp, height = 800.dp),
                onKeyEvent = { event ->
                    val down = event.type == KeyEventType.KeyDown
                    when {
                        down && (event.key == Key.Escape || event.key == Key.Q) -> exitApplication().let { true }
                        down && event.key == Key.O -> true.also { open() }
                        else -> false
                    }
                },
            ) {
                dialogs.parent = window
                MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                    Surface {
                        Screen(session, state, onOpen = { open() })
                    }
                }
            }
        }
    }
    return 0
}

/** The system's dialogs, over the window [parent] once it is shown. */
private class Dialogs {
    var parent: Frame? = null

    /**
     * A photo or a video picked in the system's dialog, which starts in the folder of the file [shown], if one is; null
     * if none is picked.
     */
    fun open(texts: Texts, shown: Source): File? {
        val dialog = FileDialog(parent, texts.get(Str.OPEN_MEDIA), FileDialog.LOAD)
        if (shown is Source.Media) dialog.directory = shown.file.absoluteFile.parent
        dialog.isVisible = true
        return dialog.files.firstOrNull()
    }
}

/** The images and the controls, which a file dropped anywhere on them opens in place of what [state] shows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Screen(session: LiveSession<ImageBitmap>, state: LiveSession.State, onOpen: () -> Unit) {
    val texts = LocalTexts.current
    val picture by session.picture.collectAsState()
    val drop = remember(session) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val file = droppedFiles(event).firstOrNull() ?: return false
                session.openFile(file)
                return true
            }
        }
    }
    Row(Modifier.dragAndDropTarget(shouldStartDragAndDrop = { droppedFiles(it).isNotEmpty() }, target = drop)) {
        Preview(picture, state, Modifier.weight(1f).fillMaxHeight(), session::setArea, session::installFfmpeg)
        Column(Modifier.width(380.dp).fillMaxHeight()) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onOpen) { Text(texts.get(Str.OPEN_MEDIA)) }
                OutlinedButton(onClick = session::openCamera) { Text(texts.get(Str.SHOW_CAMERA)) }
            }
            Controls(
                view = state.view,
                recording = false,
                onChange = session::changeView,
                onReset = session::reset,
                language = state.language,
                onLanguage = session::setLanguage,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The files a drag or a drop carries, and none if it carries something else. */
@OptIn(ExperimentalComposeUiApi::class)
private fun droppedFiles(event: DragAndDropEvent): List<File> =
    (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty().map { File(URI(it)) }

/**
 * The images laid out as [picture] has them, with their captions, or why there are none, and where [state] offers it
 * an offer to install ffmpeg, which [onInstallFfmpeg] starts.
 */
@Composable
private fun Preview(
    picture: Picture<ImageBitmap>?,
    state: LiveSession.State,
    modifier: Modifier,
    onArea: (Area) -> Unit,
    onInstallFfmpeg: () -> Unit,
) {
    val density = LocalDensity.current
    val texts = LocalTexts.current
    val captionHeight = with(density) { CAPTION_HEIGHT.roundToPx() }
    val gap = with(density) { GAP.roundToPx() }
    Box(modifier.onSizeChanged { onArea(Area(it.width, it.height, captionHeight, gap)) }) {
        val failure = state.failure
        if (failure != null) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(failure.words(texts), textAlign = TextAlign.Center)
                if (state.offersFfmpeg) FfmpegOffer(state, onInstallFfmpeg)
            }
            return@Box
        }
        if (picture == null) return@Box
        Canvas(Modifier.fillMaxSize()) { drawImage(picture.image) }
        val captions = texts.captions(picture.view, picture.differenceShare)
        picture.layout.captions.zip(captions).forEach { (box, caption) ->
            Text(
                caption,
                Modifier
                    .offset { IntOffset(box.left, box.top) }
                    .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
                    .wrapContentHeight(),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A button that installs ffmpeg through winget, which [onInstall] starts, and what came of it, as [state] has it; where
 * winget is missing, the session opens ffmpeg's download page in the browser.
 */
@Composable
private fun FfmpegOffer(state: LiveSession.State, onInstall: () -> Unit) {
    val texts = LocalTexts.current
    OutlinedButton(enabled = !state.installingFfmpeg, onClick = onInstall) {
        Text(texts.get(if (state.installingFfmpeg) Str.INSTALLING_FFMPEG else Str.INSTALL_FFMPEG))
    }
    state.ffmpegFailure?.let { Text(it.words(texts), textAlign = TextAlign.Center) }
}

/** An area's pixels as Compose draws them, through Skia. */
private fun composeImage(pixels: ByteArray, width: Int, height: Int): ImageBitmap {
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    return SkiaImage.makeRaster(info, pixels, width * 4).toComposeImageBitmap()
}
