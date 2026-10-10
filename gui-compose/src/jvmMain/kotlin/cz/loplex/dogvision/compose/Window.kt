package cz.loplex.dogvision.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cz.loplex.dogvision.common.info
import cz.loplex.dogvision.desktop.Area
import cz.loplex.dogvision.desktop.Failure
import cz.loplex.dogvision.desktop.LiveSession
import cz.loplex.dogvision.desktop.OpenDialog
import cz.loplex.dogvision.desktop.Picture
import cz.loplex.dogvision.desktop.Source
import cz.loplex.dogvision.desktop.WindowArguments
import cz.loplex.dogvision.desktop.menus
import cz.loplex.dogvision.desktop.windowIcon
import cz.loplex.dogvision.texts.Menu
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import cz.loplex.dogvision.texts.press
import cz.loplex.dogvision.ui.Controls
import cz.loplex.dogvision.ui.EXPAND_ICON
import cz.loplex.dogvision.ui.LocalOpenLists
import cz.loplex.dogvision.ui.LocalTexts
import cz.loplex.dogvision.ui.MenuButton
import cz.loplex.dogvision.ui.OpenLists
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.Frame
import java.io.File
import java.net.URI
import org.jetbrains.skia.Image as SkiaImage

/** The height of a caption under each image, and the gap between images, as in the Android app. */
private val CAPTION_HEIGHT = 40.dp
private val GAP = 6.dp

/**
 * Opens the window on what [arguments] ask for: the file given, a photo or else a video,
 * or the camera --camera names; returns once the window is closed.\
 * Its menus hold what the panel holds, and their keys press them:
 * Ctrl+O opens another file from the system's dialog, F9 or the button at the images' edge hides the controls,
 * Ctrl+M hides the menu bar, and Ctrl+Q closes it; a file dropped onto the window opens too.\
 * What it shows is a [LiveSession]'s, which the window only lays out.
 */
@Suppress("SameReturnValue")
fun showWindow(arguments: WindowArguments): Int {
    application(exitProcessOnExit = false) {
        val session = remember { LiveSession.drawnOnGpu(arguments, ::composeImage) }
        DisposableEffect(session) {
            onDispose { session.close() }
        }
        val state by session.state.collectAsState()
        val dialogs = remember { Dialogs() }
        val icon = remember { BitmapPainter(windowIcon().toComposeImageBitmap()) }
        val open = { dialogs.open(state.texts, state.source, session::openFile) }
        val lists = remember { OpenLists() }
        val chooseOutputDir = { dialogs.chooseFolder(state.texts, state.outputDir, session::setOutputDir) }
        val menus = session.menus(state, open = { open() }, chooseOutputDir = chooseOutputDir, quit = ::exitApplication)
        CompositionLocalProvider(LocalTexts provides state.texts, LocalOpenLists provides lists) {
            Window(
                onCloseRequest = ::exitApplication,
                title = state.texts.get(Str.APP_NAME),
                icon = icon,
                state = rememberWindowState(width = 1280.dp, height = 800.dp),
                onKeyEvent = { event ->
                    val key = MENU_KEYS[event.key]?.takeIf { it.withCtrl == event.isCtrlPressed }
                    when {
                        event.type != KeyEventType.KeyDown -> false

                        // A list dropped down takes the keys;
                        // a menu's key is kept from the menu bar, which would take it otherwise.
                        lists.count != 0 -> key != null

                        else -> key?.let(menus::press) ?: false
                    }
                },
            ) {
                dialogs.parent = window
                WindowMenuBar(menus, inFrame = state.menuBarShown)
                MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                    Surface {
                        Screen(session, state, menus, onOpen = { open() })
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
    private val openDialog = OpenDialog()
    private val folderDialog = OpenDialog(folder = true)

    /**
     * Tells [onPicked] a photo or a video picked in the system's dialog,
     * which starts in the folder of the file [shown], if one is.
     */
    fun open(texts: Texts, shown: Source, onPicked: (File) -> Unit) {
        openDialog.show(parent, texts.get(Str.OPEN_MEDIA), (shown as? Source.Media)?.file, texts, onPicked)
    }

    /** Tells [onPicked] a folder picked in the system's dialog, which starts in the folder [shown]. */
    fun chooseFolder(texts: Texts, shown: File, onPicked: (File) -> Unit) {
        folderDialog.show(parent, texts.get(Str.OUTPUT_FOLDER_TITLE), shown, texts, onPicked)
    }
}

/**
 * The images and the controls, which a file dropped anywhere on them opens in place of what [state] shows,
 * with [menus] behind a button at the images' edge while the menu bar is hidden.
 */
@Composable
private fun Screen(
    session: LiveSession<ImageBitmap>,
    state: LiveSession.State,
    menus: List<Menu>,
    onOpen: () -> Unit,
) {
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
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.weight(1f)
                .dragAndDropTarget(shouldStartDragAndDrop = { droppedFiles(it).isNotEmpty() }, target = drop),
        ) {
            Images(session, state, menus, onOpen, picture)
        }
        StatusBar(state)
    }
}

/**
 * The images, the buttons at their edge and the controls, as [state] has them:
 * the menu button, which drops [menus] down, only while the menu bar is hidden.
 */
@Composable
private fun RowScope.Images(
    session: LiveSession<ImageBitmap>,
    state: LiveSession.State,
    menus: List<Menu>,
    onOpen: () -> Unit,
    picture: Picture<ImageBitmap>?,
) {
    val texts = LocalTexts.current
    Preview(
        picture,
        state,
        Modifier.weight(1f).fillMaxHeight(),
        session::setArea,
        session::downloadFfmpeg,
        session::installFfmpeg,
    )
    Column {
        PanelToggle(state.panelShown, session::togglePanel)
        if (!state.menuBarShown) MenuButton(menus, LocalContentColor.current, keys = true)
    }
    if (state.panelShown) {
        Column(Modifier.width(380.dp).fillMaxHeight()) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onOpen) { Text(texts.get(Str.OPEN_MEDIA)) }
                OutlinedButton(onClick = session::toggleCamera) { Text(texts.get(Str.SHOW_CAMERA)) }
            }
            Controls(
                view = state.view,
                recording = state.recording,
                onChange = session::changeView,
                onReset = session::reset,
                camera = state.camera,
                onCamera = session::chooseCamera,
                onMirroring = session::setMirroring,
                language = state.language,
                onLanguage = session::setLanguage,
                modifier = Modifier.weight(1f),
                onCamerasOpened = session::listCamerasAgain,
                info = ::info,
            )
        }
    }
}

/** What is shown, and what the window said it did last, under the images and the controls. */
@Composable
private fun StatusBar(state: LiveSession.State) {
    val texts = LocalTexts.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val style = MaterialTheme.typography.bodySmall
        Text(state.sourceName(texts), style = style, maxLines = 1)
        state.conversionStatus?.let { conversion -> Text(conversion(texts), style = style, maxLines = 1) }
        state.recordingStatus?.let { recording -> Text(recording(texts), style = style, maxLines = 1) }
        state.status?.let { status ->
            Text(status(texts), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The button at the images' edge that hides the controls if [shown], else shows them, through [onToggle]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("MagicNumber")
private fun PanelToggle(shown: Boolean, onToggle: () -> Unit) {
    val words = LocalTexts.current.get(if (shown) Str.HIDE_PANEL else Str.SHOW_PANEL)
    TooltipArea(
        tooltip = {
            Surface(shape = MaterialTheme.shapes.small, tonalElevation = 4.dp, shadowElevation = 4.dp) {
                Text(words, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            }
        },
    ) {
        IconButton(onClick = onToggle) {
            Icon(EXPAND_ICON, contentDescription = words, modifier = Modifier.rotate(if (shown) -90f else 90f))
        }
    }
}

/** The files a drag or a drop carries, and none if it carries something else. */
@OptIn(ExperimentalComposeUiApi::class)
private fun droppedFiles(event: DragAndDropEvent): List<File> =
    (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty().map { File(URI(it)) }

/**
 * The images laid out as [picture] has them, with their captions, or why there are none,
 * and where [state] offers it an offer to download ffmpeg, which [onDownloadFfmpeg] starts,
 * or to install it through winget, which [onInstallFfmpeg] starts.
 */
@Composable
private fun Preview(
    picture: Picture<ImageBitmap>?,
    state: LiveSession.State,
    modifier: Modifier,
    onArea: (Area) -> Unit,
    onDownloadFfmpeg: () -> Unit,
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
                if (state.offersFfmpeg) FfmpegOffer(state, onDownloadFfmpeg, onInstallFfmpeg)
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
 * A button that downloads ffmpeg, which [onDownload] starts, beside one that installs it through winget,
 * which [onInstall] starts, where [state] offers winget, and what came of either, as [state] has it;
 * where winget is missing, that names ffmpeg's download page as a link.
 */
@Composable
private fun FfmpegOffer(state: LiveSession.State, onDownload: () -> Unit, onInstall: () -> Unit) {
    val texts = LocalTexts.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !state.gettingFfmpeg, onClick = onDownload) {
            val percent = state.downloadingFfmpeg
            Text(if (percent == null) texts.get(Str.DOWNLOAD_FFMPEG) else texts.get(Str.DOWNLOADING_FFMPEG, percent))
        }
        if (state.offersWinget) {
            OutlinedButton(enabled = !state.gettingFfmpeg, onClick = onInstall) {
                Text(texts.get(if (state.installingFfmpeg) Str.INSTALLING_FFMPEG else Str.INSTALL_FFMPEG))
            }
        }
    }
    state.ffmpegFailure?.let { Text(linkedWords(it, texts), textAlign = TextAlign.Center) }
}

/** [failure]'s words, with its link, where it has one, as a link that opens it in the browser when it is clicked. */
@Composable
private fun linkedWords(failure: Failure, texts: Texts): AnnotatedString {
    val words = failure.words(texts)
    val link = failure.link
    val at = link?.let { words.indexOf(it) } ?: -1
    if (link == null || at < 0) return AnnotatedString(words)
    val style = TextLinkStyles(SpanStyle(MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        append(words.substring(0, at))
        withLink(LinkAnnotation.Url(link, style)) { append(link) }
        append(words.substring(at + link.length))
    }
}

/** An area's pixels as Compose draws them, through Skia. */
@Suppress("MagicNumber")
private fun composeImage(pixels: ByteArray, width: Int, height: Int): ImageBitmap {
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    return SkiaImage.makeRaster(info, pixels, width * 4).toComposeImageBitmap()
}
