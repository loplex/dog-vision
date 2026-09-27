# Using dog-vision

What each program shows and how it is driven. Building them is [Building it](building.md)'s.

- [The Android app](#the-android-app) — the controls, a photo or a video, saving at full size,
  recording, the language, and how it differs from the desktop program.
- [The web page](#the-web-page) — what a browser needs for the camera, snapshots and recording.
- [The command line](#the-command-line) — converting a photo, `--species` and the other options.
- [The desktop window](#the-desktop-window) — `dog-vision` in Compose and `dog-vision-swing` in
  Swing, their GL on Linux and Windows, ffmpeg for a video or the camera, and what they have not run
  on yet.

## The Android app

The app opens on the back camera, with the scene as it is and as a dog sees it.

- **The button with two arrows switches to the front camera**, and back; the front camera's image
  is shown mirrored, as a mirror shows a face, and saved and recorded as shown.
- **The images are laid out to show them largest**: side by side on a wide screen, one above another
  on a tall one, each with a caption under it saying what it shows.
- **The controls are beside the images on a wide screen and under them on a tall one.** The button
  with three sliders over the images hides or shows them.
- **Each control and each fact has an info button** that says what it means, in a few paragraphs.

### The controls

The controls are grouped in sections, as in the desktop window, and a tap on a section's title opens
or closes it.
The web page and the desktop window show the same ones.

| Control                       | Does                                                     |
|-------------------------------|----------------------------------------------------------|
| *Species*                     | picks the animal                                         |
| *Selected species*            | its cones, their sources, its RNL factors and its acuity |
| *Adaptation to scene [%]*     | adapts the cones to the scene instead of to daylight     |
| *Simulation strength [%]*     | blends the simulation with the original                  |
| *Colour saturation*           | how saturated the colours come out                       |
| *Blur to the species' acuity* | removes the detail finer than the species resolves       |
| *Image spans [degrees]*       | how wide the image is in degrees, which sets the blur    |
| *Side by side*                | a second image beside the simulation, or not             |
| *Compared with*               | the original, or another species, in that second image   |
| *Map of differences*          | a third image marking where the first two differ         |
| *Reset*                       | every control back to where it started                   |
| *Language*                    | the app's language; *As the system* follows the phone    |

The controls, which camera is shown, and the photo or video shown instead (a video from its start)
stay as they were when Android ends the app in the background to free memory.
A photo or a video that cannot be read by then, as when it was deleted meanwhile, gives way to the
camera, and a message says so.

### A photo or a video instead of the camera

- **The picture button opens a photo or a video** through the system's photo picker, which needs no
  storage permission.
- **A photo is shown scaled down** to 1280 pixels on its longest side, so that the controls stay
  quick, and turned as its EXIF orientation says.
- **A video plays at its own rate, over and over, without its sound**, scaled down as a photo is and
  turned as its rotation says.
- **An HDR video is tone-mapped to SDR** as its conversion is, so that it looks as it will once
  saved, where the GPU can do it (with `GL_EXT_YUV_target`); where not, as on the emulator, it looks
  flat, its HDR frames taken as SDR.
- **The camera button goes back to the camera.**
- **A photo or a video can be opened without allowing the camera**, which the app asks for only to
  show it.

### Saving

Images go to *Pictures/Dog vision* and videos to *Movies/Dog vision*, where the gallery shows them:

- **The arrow button saves a snapshot** of the view as shown, at the size the images are rendered:
  the camera's resolution, or a photo's or a video's scaled-down view.
  It is called `dog-<species>-<time>.png`, or `dog-<left>-vs-<species>-<time>.png` when another
  species is compared.
- **The corners button saves the photo or the video at its full size**, as the view shows it now,
  with the same name ending in `-full.png` or `-full.mp4`.
  A message says how far it has got, and the cross that replaces the button meanwhile cancels it.
- **A photo is converted on the CPU.**
- **A video is converted by Media3's Transformer on the GPU**, with the original's sound:
  - it is encoded as H.265 where the phone has an encoder for it, and as H.264 where not;
  - it is scaled down, keeping its shape, where the encoder or the GPU cannot take the view's size,
    and the message says so;
  - an HDR video is tone-mapped to SDR first, since the model works on SDR; a GPU without
    `GL_EXT_YUV_target`, as the emulator's, cannot, and the conversion then fails;
  - it stops if Android ends the app in the background.

Before Android 10, saving asks for the storage permission.

### Recording the view

- **The ring button records the view as shown**, every change included, until the square button
  stops it.
- **The video has 30 frames a second, without sound**, each frame repeated for as long as it was
  shown, as the desktop program records.
- **It is called `dog-<species>-<time>.mp4`** and goes to *Movies/Dog vision*.
- **While it records, whatever would change the video's size is locked**: the source and the
  camera, *Side by side*, *Map of differences*, and the screen's orientation.
- **It stops when the app leaves the screen**, and the video recorded so far is saved.

### Language

- **The app speaks English and Czech**, with the desktop program's Czech texts.
- **It starts in the phone's language**, and in English when the phone's is neither.
- **The *Language* choice at the end of the controls overrides it**, and so does the per-app
  language setting of Android 13 and later; Android remembers the choice.

### How it differs from the desktop program

- **Files go to the gallery**, not to an output folder chosen in the app.
- **The camera is the back or the front one**, not one picked by its number, and the front one is
  mirrored, where the desktop program shows a webcam's image as it comes.
- **A photo or a video converted at full size is named like a snapshot**, not `<name>.dog.png` or
  `<name>.dog.mp4` after the original: the system photo picker does not tell an app a file's name.
- **Videos are written by the phone's encoders**, not ffmpeg's, so a video too large for them is
  scaled down, where the desktop program writes any size.
- **Recording locks the screen's orientation**, since turning the phone would change the video's
  size.
- **The second image's choice is called *Compared with***, not *Left image*: on a tall screen it is
  the top one.
- **Tooltips are info buttons**, since a touch screen has no pointer to rest.
- **There is no command line**, so *Reset* returns to the app's defaults, and `--info` has no
  counterpart.

## The web page

- **The built `index.html` opens as it is**, from the folder or from any static server: the page
  fetches nothing, and its wording is compiled into `dog-vision.js`.
- **The browser needs WebGL 2**; without it the page says so.
- **Its wording is English or Czech**, as chosen at the end of its panel, which the browser
  remembers, or else the browser's language where there are strings for it.

### A photo or a video

- **Open a photo or a video opens either**, as does dropping one on the images.
- **A photo is scaled down to 1280 pixels** on its longest side, as the app's is.
- **A video plays over and over without its sound**, scaled down as a photo is, turned as its file
  says, and paused while the page is hidden.
- **What plays is what the browser decodes**: its codecs, and its own tone mapping of an HDR video.

### The camera

- **Camera starts the camera** when clicked, and the browser asks first whether the page may use
  it.
- **It shows the back camera first**, where the device tells them apart, asked for at 1280 x 720 as
  the app's frames are; a camera without 1280 x 720 gives the size it has nearest, such as 640 x
  480.
- **Switch camera goes to the front camera**, and back; it shows only where the browser knows of two
  cameras or more.
- **Every camera's image is mirrored** but one that says it faces away from the viewer: a laptop's
  webcam often says nothing of where it faces.
- **The camera stops while the page is hidden**, and starts again when it is shown; opening a photo
  or a video stops it.

#### Caveat: the camera needs a secure context

The browser offers a camera only to a page served over `https:` or from `localhost`, or opened as a
file, which Chrome 154 and Firefox 156 count as one.
Served over plain `http:` from elsewhere, the page shows no Camera button.

### Saving and recording

- **Save snapshot downloads the view as shown**, as a PNG named as the app names it, at the size the
  photo, the video's or the camera's frame is shown at; the photo and the video at full size are
  only the app's.
- **The snapshot needs `CompressionStream`**, which Chrome 80, Firefox 113 and Safari 16.4 are the
  first to have.
- **Record video records the view as shown** until Stop recording, as the app records it: the images
  at the size they are composed, in the arrangement they had at the start, without captions or
  sound, at up to 30 frames a second.
- **The browser saves it as `dog-<species>-<time>.mp4`**, or as `.webm` where it records no MP4:
  Chrome 154 records H.264 in MP4, Firefox 156 VP8 in WebM. A WebM a browser records states no
  duration, so a player may not seek in it.
- **While it records, whatever would change the video's size is locked**: the source, the camera,
  *Side by side* and *Map of differences*.
- **It stops when the page is hidden**, and the video recorded so far is saved.

#### Caveat: Firefox on Android records no video of a file

In Firefox 156 on Android, a video's frames come out magenta in the recording.
The camera and a photo record there.

## The command line

```sh
dog-vision-cli --species cat --compare dog photo.jpg            # from the deb or the rpm
java -jar dog-vision-cli.jar --species cat --compare dog photo.jpg
```

- **On Linux it installs as a package of its own, `dog-vision-cli`**, from its deb or its rpm, and
  runs as `dog-vision-cli` from the `PATH` on the system's Java 17 or newer, which the package
  manager installs with it where there is none; a headless Java is enough, as it opens no window.
- **Anywhere else, the JAR runs on a Java 17 or newer**: `./gradlew :cli:uberJar` builds it, with
  everything it needs.
- **It converts a photo at full size**, as the desktop program's `dog-vision photo.jpg` does: it
  writes `photo.dog.png` next to the photo, or into `--output-dir`, and says what share of the
  pixels differ when `--difference` asks for the map.
- **It takes the desktop program's options** for the view: `--species`, `--compare`,
  `--difference`, `--adaptation`, `--strength`, `--chroma-scale`, `--acuity` and `--fov`; `--help`
  lists them. `--info`, a video and the window are not in it.
- **A photo is turned as its EXIF orientation says**, as OpenCV turns it for the desktop program.
- **It speaks the system's language**, English or Czech: the JVM takes it from `LC_ALL`,
  `LC_MESSAGES` or `LANG`, and not from `LANGUAGE`. The desktop program's command line speaks
  English only.

## The desktop window

```sh
dog-vision                          # the camera, /dev/video0 on Linux
dog-vision --window photo.jpg       # a photo, or a video played over and over
dog-vision --species cat photo.jpg  # converts it, as the command line does
```

It is a first version, for Linux and Windows on x86-64, of a window to replace the desktop
program's.

- **On Linux it installs from the deb or the rpm `dog-vision`**, as `dog-vision` on the `PATH` and
  *Dog vision* in the desktop's menu (*Psí vidění* in Czech), on the system's Java 17 or newer,
  which the package manager installs with it where there is none.
  It recommends `dog-vision-cli`, the command line alone, which is a package of its own.
- **The tar.gz runs without installing**, unpacked anywhere, as `dog-vision/bin/dog-vision`, on a
  Java of its own; on Windows, the MSI installs it with one as well.
- **Its launcher takes the Java in `JAVA_HOME`**, else the one on the `PATH`, else the newest in
  `/usr/lib/jvm` or `/usr/lib64/jvm`, whichever is first 17 or newer.
- **It takes the command line's options**, and converts a photo given without `--window` as the
  command line does.
- **A photo is scaled down to 1280 pixels** for the view, as the app's is.
- **Open a photo or a video opens another one** from the system's dialog, which starts in the folder
  of the file shown; the o key opens the dialog too, as it opens the desktop program's, and a file
  dropped anywhere on the window opens as well.
- **Camera goes back to the camera** the command line names.
- **q or Escape closes it**, as it closes the desktop program's window.
- **It has no menus, no settings, no snapshots and no recording yet.**

### The same window in Swing: `dog-vision-swing`

```sh
dog-vision-swing --window photo.jpg   # as dog-vision, with the same options, keys and dialog
```

- **It shows what the Compose window shows, from the same state**, the `LiveSession` in
  [`desktop-core`](../desktop-core): the same images, drawn by the same passes, and the same
  controls, in Swing's own widgets with [FlatLaf](https://www.formdev.com/flatlaf/).
- **It needs neither Compose nor skiko**, so its packages leave out Compose's JARs and skiko's
  natives.
- **On Linux it installs from the deb or the rpm `dog-vision-swing`**, as `dog-vision-swing` on the
  `PATH` and *Dog vision (Swing)* in the desktop's menu (*Psí vidění (Swing)* in Czech), beside
  `dog-vision` where both are installed.
  Its tar.gz runs as `dog-vision-swing/bin/dog-vision-swing`, and on Windows its MSI installs it.
- **It is light or dark as the desktop asks**: on Linux as the desktop portal's colour scheme says,
  which GNOME and KDE set, on Windows as `AppsUseLightTheme` says, and light where neither can be
  read.
- **It draws the images at the screen's own pixels**, not at Swing's scaled ones, so that they are
  as sharp on a HiDPI screen as in the Compose window.

The sections below hold for both windows.

### The GPU it draws on

- **On Linux it needs EGL with Mesa's device platform** (`EGL_EXT_platform_device`) for an OpenGL ES
  3 context, and takes the first device with a DRM render node; with none, it renders on the CPU and
  says so on its standard error.
- **On Windows it draws through ANGLE**, OpenGL ES 3 over Direct3D 11, as Chrome draws WebGL there;
  ANGLE comes with it.
- **Where ANGLE cannot start, WGL draws**, the graphics driver's own desktop OpenGL 3.3. `--gl angle`
  or `--gl wgl` picks one alone.

### A video or the camera, through ffmpeg

- **A video or the camera comes through the system's `ffmpeg`**, which must be on the `PATH` with
  `ffprobe`; where either cannot run, the window says so in place of the video or the camera.
- **A video plays at its own speed**, without its sound, turned as its file says and scaled down as
  a photo is; an HDR video is not tone mapped.
- **`--camera N` is `/dev/videoN` on Linux**, through Video4Linux, and on Windows the Nth camera
  ffmpeg lists through DirectShow, counted from 0.
- **The camera is asked for Motion-JPEG at 1280 x 720**, which a USB webcam gives at 30 frames a
  second, and for whatever it gives where it has none. It is not mirrored, as the desktop program's
  is not.

#### On Windows, the window offers to install ffmpeg

- **Windows has no ffmpeg of its own**, so the window offers to install it through winget, as Gyan's
  build (`winget install --id Gyan.FFmpeg`) for this user alone.
- **It shows the video or the camera once ffmpeg is found**, without starting again.
- **Where winget is missing**, as on a Windows without Microsoft's App Installer, the window opens
  ffmpeg's download page in the browser and names it.

### Caveat: on Windows, neither window has run on a real machine

- **The Compose window has run under Wine only**, on Linux.
- **The Swing window has not run on Windows at all**: its MSI is built, installed and removed there,
  and its command line runs, but its window has not been opened.
- **Their tests and their MSIs run on a Windows server without a GPU**, as
  [Developing it](developing.md#caveat-windows-is-tested-without-a-gpu-and-under-wine) says.
