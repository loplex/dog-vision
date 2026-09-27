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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
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
import cz.loplex.dogvision.cli.readPhoto
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import cz.loplex.dogvision.ui.Controls
import cz.loplex.dogvision.ui.LocalTexts
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import kotlin.concurrent.thread

/** What the window shows: a file, which is a photo or else a video played over and over, or a camera. */
sealed interface Source {
    class Media(val file: File) : Source

    class Camera(val index: Int) : Source
}

/** Why nothing is shown, worded when it is shown, in the language chosen then. */
private typealias Failure = (Texts) -> String

/** The height of a caption under each image, and the gap between images, as in the Android app. */
private val CAPTION_HEIGHT = 40.dp
private val GAP = 6.dp

/**
 * Opens the window on what [arguments] ask for: the file given with --window, a photo or else a video, or the camera
 * --camera names; returns once the window is closed. Another file is opened from the system's dialog, from the o key
 * as in the Python program's window, or dropped onto the window; q or Escape closes it, as in the Python program's.
 */
fun showWindow(arguments: Arguments): Int {
    val start = arguments.file?.let(Source::Media) ?: Source.Camera(arguments.camera)
    application(exitProcessOnExit = false) {
        var language by remember { mutableStateOf("") }
        val texts = remember(language) {
            Texts.forLanguages(listOf(language.ifEmpty { Locale.getDefault().toLanguageTag() }))
        }
        var source by remember { mutableStateOf(start) }
        val dialogs = remember { Dialogs() }
        CompositionLocalProvider(LocalTexts provides texts) {
            Window(
                onCloseRequest = ::exitApplication,
                title = texts.get(Str.APP_NAME),
                state = rememberWindowState(width = 1280.dp, height = 800.dp),
                onKeyEvent = { event ->
                    val down = event.type == KeyEventType.KeyDown
                    when {
                        down && (event.key == Key.Escape || event.key == Key.Q) -> exitApplication().let { true }
                        down && event.key == Key.O -> true.also { dialogs.open(texts, source)?.let { source = it } }
                        else -> false
                    }
                },
            ) {
                dialogs.parent = window
                MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                    Surface {
                        Screen(
                            source,
                            arguments,
                            language,
                            onLanguage = { language = it },
                            onSource = { source = it },
                            onOpen = { dialogs.open(texts, source)?.let { source = it } },
                        )
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
    fun open(texts: Texts, shown: Source): Source? {
        val dialog = FileDialog(parent, texts.get(Str.OPEN_MEDIA), FileDialog.LOAD)
        if (shown is Source.Media) dialog.directory = shown.file.absoluteFile.parent
        dialog.isVisible = true
        return dialog.files.firstOrNull()?.let(Source::Media)
    }
}

/** The images and the controls, which a file dropped anywhere on them opens in place of what [source] shows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Screen(
    source: Source,
    arguments: Arguments,
    language: String,
    onLanguage: (String) -> Unit,
    onSource: (Source) -> Unit,
    onOpen: () -> Unit,
) {
    val texts = LocalTexts.current
    var view by remember { mutableStateOf(arguments.windowView) }
    var picture by remember { mutableStateOf<Picture?>(null) }
    // Why the source cannot be shown, which another source clears, and why nothing can be drawn, which stays.
    var sourceFailure by remember { mutableStateOf<Failure?>(null) }
    var drawFailure by remember { mutableStateOf<Failure?>(null) }
    val renderer = remember {
        Renderer(
            arguments.windowsGl,
            onPicture = { EventQueue.invokeLater { picture = it } },
            onFailure = { message -> EventQueue.invokeLater { drawFailure = { it.get(Str.DRAW_FAILED, message) } } },
        )
    }
    DisposableEffect(renderer) {
        onDispose { renderer.close() }
    }
    // Disposed before the renderer, and the source shown before is closed before another starts.
    DisposableEffect(source) {
        sourceFailure = null
        val feed = startFeed(source, renderer) { failed -> EventQueue.invokeLater { sourceFailure = failed } }
        onDispose { feed.close() }
    }
    LaunchedEffect(view) { renderer.setView(view) }
    val currentOnSource by rememberUpdatedState(onSource)
    val drop = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val file = droppedFiles(event).firstOrNull() ?: return false
                currentOnSource(Source.Media(file))
                return true
            }
        }
    }
    Row(Modifier.dragAndDropTarget(shouldStartDragAndDrop = { droppedFiles(it).isNotEmpty() }, target = drop)) {
        Preview(picture, drawFailure ?: sourceFailure, Modifier.weight(1f).fillMaxHeight(), renderer::setArea)
        Column(Modifier.width(380.dp).fillMaxHeight()) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onOpen) { Text(texts.get(Str.OPEN_MEDIA)) }
                OutlinedButton(onClick = { onSource(Source.Camera(arguments.camera)) }) {
                    Text(texts.get(Str.SHOW_CAMERA))
                }
            }
            Controls(
                view = view,
                recording = false,
                onChange = { change -> view = change(view) },
                onReset = { view = arguments.windowView },
                language = language,
                onLanguage = onLanguage,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The files a drag or a drop carries, and none if it carries something else. */
@OptIn(ExperimentalComposeUiApi::class)
private fun droppedFiles(event: DragAndDropEvent): List<File> =
    (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty().map { File(URI(it)) }

/** The images laid out as [picture] has them, with their captions, or why there are none. */
@Composable
private fun Preview(picture: Picture?, failure: Failure?, modifier: Modifier, onArea: (Area) -> Unit) {
    val density = LocalDensity.current
    val texts = LocalTexts.current
    val captionHeight = with(density) { CAPTION_HEIGHT.roundToPx() }
    val gap = with(density) { GAP.roundToPx() }
    Box(modifier.onSizeChanged { onArea(Area(it.width, it.height, captionHeight, gap)) }) {
        if (failure != null) {
            Text(failure(texts), Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center)
            return@Box
        }
        if (picture == null) return@Box
        Canvas(Modifier.fillMaxSize()) { drawImage(picture.bitmap) }
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
 * Starts showing [source] through [renderer], on a thread of its own, since a large photo takes a moment to read and
 * ffmpeg to open a camera; [onFailure] is told why it cannot be shown. The feed it returns stops what it started, and a
 * photo read after it is closed is not shown.
 */
private fun startFeed(source: Source, renderer: Renderer, onFailure: (Failure) -> Unit): AutoCloseable {
    var feed: FfmpegFeed? = null
    var closed = false
    val lock = Object()
    thread(name = "dog-vision-source", isDaemon = true) {
        val started = try {
            when (source) {
                is Source.Media -> {
                    val photo = readPhoto(source.file)
                    if (photo == null) {
                        FfmpegFeed.video(
                            source.file,
                            { renderer.show(it, live = true) },
                            { reason -> onFailure { it.get(Str.VIDEO_FAILED, source.file.name, reason) } },
                        )
                    } else {
                        val frame = frameOf(preview(photo))
                        synchronized(lock) { if (!closed) renderer.show(frame, live = false) }
                        null
                    }
                }

                is Source.Camera -> FfmpegFeed.camera(
                    source.index,
                    { renderer.show(it, live = true) },
                    { reason -> onFailure { it.get(Str.CAMERA_FAILED, reason) } },
                )
            }
        } catch (error: FfmpegMissing) {
            onFailure { it.get(Str.FFMPEG_MISSING, error.program) }
            null
        } catch (error: IOException) {
            val reason = error.message.orEmpty()
            onFailure { texts ->
                when (source) {
                    is Source.Camera -> texts.get(Str.CAMERA_FAILED, reason)
                    is Source.Media -> texts.get(Str.MEDIA_FAILED, source.file.name) + ": $reason"
                }
            }
            null
        }
        synchronized(lock) {
            if (closed) started?.close() else feed = started
        }
    }
    // A feed that starts after this is closed closes itself.
    return AutoCloseable {
        synchronized(lock) {
            closed = true
            feed?.close()
        }
    }
}
