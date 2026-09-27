package cz.loplex.dogvision.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.loplex.dogvision.MainViewModel
import cz.loplex.dogvision.PREVIEW_LONGEST_SIDE
import cz.loplex.dogvision.R
import cz.loplex.dogvision.Source
import cz.loplex.dogvision.camera.CameraFeed
import cz.loplex.dogvision.savingNeedsPermission
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.video.VideoFeed

private val CAPTION_HEIGHT = 40.dp
private val GAP = 6.dp

@Composable
fun MainScreen(model: MainViewModel) {
    val context = LocalContext.current
    val source by model.source.collectAsStateWithLifecycle()
    val converting by model.converting.collectAsStateWithLifecycle()
    val recorder by model.recorder.collectAsStateWithLifecycle()
    val recording = recorder != null
    var cameraAllowed by remember {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        mutableStateOf(permission == PackageManager.PERMISSION_GRANTED)
    }
    val bothCameras = remember {
        val features = context.packageManager
        features.hasSystemFeature(PackageManager.FEATURE_CAMERA) &&
            features.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
    }
    val askForCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraAllowed = it
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(model::openMedia)
    }
    val save = rememberSaving(model)
    WhileRecording(model, recording)
    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        WithControls(
            model,
            buttons = {
                // The source cannot change while recording, as its size would change the video's.
                if (!recording) {
                    ImageButton(R.drawable.ic_photo, Str.OPEN_MEDIA) {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                    if (source != Source.Camera) {
                        ImageButton(R.drawable.ic_camera, Str.SHOW_CAMERA, onClick = model::openCamera)
                    } else if (cameraAllowed && bothCameras) {
                        ImageButton(R.drawable.ic_switch_camera, Str.SWITCH_CAMERA, onClick = model::switchCamera)
                    }
                }
                ImageButton(R.drawable.ic_save, Str.SAVE_SNAPSHOT) { save(model::saveSnapshot) }
                if (recording) {
                    ImageButton(R.drawable.ic_stop, Str.STOP_RECORDING, RECORDING_RED, model::stopRecording)
                } else {
                    ImageButton(R.drawable.ic_record, Str.RECORD) { save(model::startRecording) }
                }
                when {
                    converting -> {
                        ImageButton(R.drawable.ic_cancel, Str.CANCEL_CONVERSION, onClick = model::cancelConversion)
                    }

                    source is Source.Photo -> ImageButton(R.drawable.ic_full_size, Str.SAVE_FULL_SIZE) {
                        save(model::convertPhoto)
                    }

                    source is Source.Video -> ImageButton(R.drawable.ic_full_size, Str.SAVE_VIDEO_FULL_SIZE) {
                        save(model::convertVideo)
                    }
                }
            },
        ) {
            Images(model)
            when (val shown = source) {
                Source.Camera -> if (cameraAllowed) {
                    Camera(model)
                } else {
                    AskForCamera { askForCamera.launch(Manifest.permission.CAMERA) }
                }

                is Source.Video -> Video(model, shown)

                is Source.Photo -> Unit
            }
        }
        val message by model.message.collectAsStateWithLifecycle()
        message?.let {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .clickable(onClick = model::dismissMessage),
            ) {
                Text(it, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

/**
 * Runs a saving action, first asking for the storage permission where saving to Pictures needs it;
 * without it the action is not run, and the model says why.
 */
@Composable
private fun rememberSaving(model: MainViewModel): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val storageNeeded = text(Str.STORAGE_NEEDED)
    var waiting by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) waiting?.invoke() else model.say(storageNeeded)
        waiting = null
    }
    return { action ->
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (!savingNeedsPermission || granted) {
            action()
        } else {
            waiting = action
            ask.launch(permission)
        }
    }
}

/** Why the camera is needed, and a button that asks for it. */
@Composable
private fun AskForCamera(onAsk: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text(Str.CAMERA_NEEDED), color = Color.White, textAlign = TextAlign.Center)
            Button(onClick = onAsk) { Text(text(Str.ALLOW_CAMERA)) }
        }
    }
}

/** An icon that does what [description] says, which a long press shows under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageButton(icon: Int, description: Str, tint: Color = Color.White, onClick: () -> Unit) {
    val text = text(description)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) {
            Icon(painterResource(icon), contentDescription = text, tint = tint)
        }
    }
}

private val RECORDING_RED = Color(0xFFFF5252)

/**
 * While [recording], keeps the screen turned as it is, so that the layout of the images, and with it
 * the video's size, stays; and stops the recording when the screen stops, as the view is no longer
 * drawn then.
 */
@Composable
private fun WhileRecording(model: MainViewModel, recording: Boolean) {
    val activity = LocalActivity.current
    DisposableEffect(recording) {
        if (recording) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose {
            if (recording) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LifecycleStartEffect(model) {
        onStopOrDispose { model.stopRecording() }
    }
}

/**
 * [images] with the controls beside them on a wide screen and under them on a tall one, where they
 * take at most [CONTROLS_SHARE] of the height. [buttons] go over the images' top right corner,
 * followed by one that hides or shows the controls. On a tall screen, the room that hiding them
 * gives the images can turn them from side by side to one above another, as layOut shows them larger.
 *
 * The images move between the two as the screen turns, rather than being made anew: made anew, the
 * camera would start again, and the surface would lose its GL context and the frames with it.
 */
@Composable
private fun WithControls(model: MainViewModel, buttons: @Composable () -> Unit, images: @Composable () -> Unit) {
    val view by model.view.collectAsStateWithLifecycle()
    val recorder by model.recorder.collectAsStateWithLifecycle()
    var shown by rememberSaveable { mutableStateOf(true) }
    val currentButtons by rememberUpdatedState(buttons)
    val currentImages by rememberUpdatedState(images)
    val imagesWithButtons = remember {
        movableContentOf { boxModifier: Modifier ->
            Box(boxModifier) {
                currentImages()
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.large),
                ) {
                    currentButtons()
                    ImageButton(R.drawable.ic_tune, Str.TOGGLE_CONTROLS) { shown = !shown }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        val controlsHeight = maxHeight * CONTROLS_SHARE
        val controls = @Composable { panelModifier: Modifier ->
            Surface(panelModifier, color = MaterialTheme.colorScheme.surface) {
                Controls(
                    view,
                    recorder != null,
                    model::update,
                    model::reset,
                    language = AppCompatDelegate.getApplicationLocales().toLanguageTags(),
                    // Android remembers the choice, and recreates the activity in the language chosen.
                    onLanguage = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it)) },
                )
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                imagesWithButtons(Modifier.weight(1f).fillMaxHeight())
                if (shown) controls(Modifier.width(CONTROLS_WIDTH).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                imagesWithButtons(Modifier.weight(1f).fillMaxWidth())
                if (shown) controls(Modifier.fillMaxWidth().heightIn(max = controlsHeight))
            }
        }
    }
}

private val CONTROLS_WIDTH = 360.dp
private const val CONTROLS_SHARE = 0.45f

/** The back or the front camera, as the model says, feeding the model's frames while the screen is started. */
@Composable
private fun Camera(model: MainViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val display = LocalView.current.display
    val front by model.frontCamera.collectAsStateWithLifecycle()
    val feed = remember(owner) { CameraFeed(context, owner, model.frames, model::onCameraError) }
    // Declared first, so disposed of last: the feed is released once, after it last started.
    DisposableEffect(feed) {
        onDispose(feed::release)
    }
    DisposableEffect(feed, front) {
        feed.start(front, display.rotation)
        onDispose { }
    }
    // Every turn of the display, including one of 180 degrees, which changes no configuration.
    DisposableEffect(feed, display) {
        val displays = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == display.displayId) feed.setRotation(display.rotation)
            }
        }
        displays.registerDisplayListener(listener, null)
        onDispose { displays.unregisterDisplayListener(listener) }
    }
}

/** The video, feeding the model's frames while the screen is started, and paused while it is stopped. */
@Composable
private fun Video(model: MainViewModel, video: Source.Video) {
    val context = LocalContext.current.applicationContext
    val feed = remember(video) {
        VideoFeed(context, video.uri, model.frames, PREVIEW_LONGEST_SIDE, model::onVideoError)
    }
    // Declared first, so disposed of last: the feed is paused before it is released.
    DisposableEffect(feed) {
        onDispose(feed::release)
    }
    LifecycleStartEffect(feed) {
        feed.play()
        onStopOrDispose { feed.pause() }
    }
}

/** The images as the renderer lays them out, each with its caption under it. */
@Composable
private fun Images(model: MainViewModel) {
    val texts = LocalTexts.current
    val density = LocalDensity.current
    val view by model.view.collectAsStateWithLifecycle()
    val recorder by model.recorder.collectAsStateWithLifecycle()
    val drawn by model.drawn.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        ViewSurface(
            frames = model.frames,
            view = view,
            captionHeight = with(density) { CAPTION_HEIGHT.roundToPx() },
            gap = with(density) { GAP.roundToPx() },
            onDrawn = model::onDrawn,
            bindCapture = { model.capture = it },
            recorder = recorder,
            modifier = Modifier.fillMaxSize(),
        )
        val shown = drawn ?: return@Box
        if (shown.layout.captions.size != view.images) return@Box // a layout of the view before
        texts.captions(view, shown.differenceShare).zip(shown.layout.captions).forEach { (caption, box) ->
            with(density) {
                Text(
                    caption,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .offset { IntOffset(box.left, box.top) }
                        .size(box.width.toDp(), box.height.toDp())
                        .padding(top = 4.dp),
                )
            }
        }
    }
}
