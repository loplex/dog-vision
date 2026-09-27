# dog-vision

*A camera, a photo or a video, shown with the colours a dog — or another animal — can tell
apart.*

This is a Kotlin port of [dog-vision](https://github.com/loplex/dog-vision-python), a desktop program in
Python, as an Android app.
It simulates the same species with the same model, and the desktop program's README explains that
model and the science behind it.
A [web page](#the-web-page) shows a photo or a camera's live image the same way in a browser, a
[command line](#the-command-line) on the JVM converts a photo as the desktop program's does, and a
[desktop window](#the-desktop-window) for Linux and Windows, a first version, shows a photo, a
video or a camera.

- [Using the app](#using-the-app) — the camera, a photo or a video, the controls, saving, recording,
  the language.
- [How it differs from the desktop program](#how-it-differs-from-the-desktop-program) — what
  works the Android way.
- [The model](#the-model) — where the explanation of the simulation lives.
- [Building it](#building-it) — the JDK, the Android SDK, `./gradlew`, the desktop packages.
- [How the code is laid out](#how-the-code-is-laid-out) — `core`, `gl`, `texts`, `ui`, `app`,
  `web`, `cli`, `desktop`, `testing`, the GPU renderer.
- [Checking it](#checking-it) — `check`, `:core:jvmTest`, `:core:allTests`, `:texts:allTests`,
  `:ui:jvmTest`, `:cli:jvmTest`, `:desktop:jvmTest`, `:web:jsTest`,
  `:app:connectedDebugAndroidTest`, `:app:lintDebug`, `ktlintFormat`, `tools/check_links.py`,
  `tools/reference_values.py`, `tools/make_test_videos.sh`.
- [License](#license)

## Using the app

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
stay as they were when Android ends the app in the background to free memory. A photo or a video
that cannot be read by then, as when it was deleted meanwhile, gives way to the camera, and a
message says so.

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

## How it differs from the desktop program

### The Android way

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

## The model

The simulation is the desktop program's, ported to Kotlin in [`core`](core), and the desktop
README explains it:

- [How it works](https://github.com/loplex/dog-vision-python#how-it-works) — the model in five steps.
- [Species](https://github.com/loplex/dog-vision-python#species) — every species, its cone peaks and their
  sources.
- [Colour saturation](https://github.com/loplex/dog-vision-python#colour-saturation) — the fixed and the
  RNL scale.
- [Acuity](https://github.com/loplex/dog-vision-python#acuity) — the blur, and why it needs the field of
  view.
- [Map of differences](https://github.com/loplex/dog-vision-python#map-of-differences) — what the red
  means.
- [What it cannot show](https://github.com/loplex/dog-vision-python#what-it-cannot-show) — its limits.
- [References](https://github.com/loplex/dog-vision-python#references) — every source in full.

## Building it

The build needs:

- **a JDK 17 or newer**; Gradle itself is fetched by the wrapper, and Node.js, which runs `core`'s
  tests in JavaScript, by the Kotlin Gradle plugin;
- **the Android SDK with platform 36**, found through `ANDROID_HOME` or `sdk.dir` in
  `local.properties`, which Android Studio and IntelliJ IDEA write when they open the project;
- **Google Chrome** for the web page's tests, found on the `PATH` as `google-chrome` or through
  `CHROME_BIN`.

```sh
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug      # onto the connected phone or emulator
```

The phone needs Android 8.0 (API 26) or later, and OpenGL ES 3.0.

### The web page

```sh
./gradlew :web:jsBrowserDevelopmentRun   # serves the page, and builds it again on every change
./gradlew :web:jsBrowserDistribution     # web/build/dist/js/productionExecutable
```

- **The built `index.html` opens as it is**, from the folder or from any static server: the page
  fetches nothing, and its wording is compiled into `dog-vision.js`.
- **The browser needs WebGL 2**; without it the page says so.
- **Open a photo or a video opens either**, as does dropping one on the images.
  - **A video plays over and over without its sound**, scaled down to 1280 pixels as a photo is,
    turned as its file says, and paused while the page is hidden.
  - **Firefox 156 on Android draws a video into a 2D canvas blank**, where the page turns and
    scales it for every other browser: there the page reads the turn from the MP4's track header,
    and scales the frame down bilinearly on the GPU.
  - **What plays is what the browser decodes**: its codecs, and its own tone mapping of an HDR
    video.
- **Camera starts the camera** when clicked, and the browser asks first whether the page may use
  it; it shows the back camera first, where the device tells them apart, asked for at 1280 x 720 as
  the app's frames are.
  - **The frame is the camera's own**, neither cropped nor scaled by the browser: a camera without
    1280 x 720 gives the size it has nearest, such as 640 x 480. Asked otherwise, Firefox 156 on
    Android held upright crops the frame to its middle square.
  - **Switch camera goes to the front camera**, and back; it shows only where the browser knows of
    two cameras or more.
  - **Every camera's image is mirrored** but one that says it faces away from the viewer: a laptop's
    webcam often says nothing of where it faces.
  - **The camera stops while the page is hidden**, and starts again when it is shown; opening a
    photo or a video stops it.
  - **The browser offers a camera only in a secure context**: a page served over `https:` or from
    `localhost`, or opened as a file, which Chrome 154 and Firefox 156 count as one. Served over
    plain `http:` from elsewhere, the page shows no Camera button.
- **Save snapshot downloads the view as shown**, as a PNG named as the app names it, at the size
  the photo, the video's or the camera's frame is shown at; the photo and the video at full size are
  only the app's.
  - **The PNG has no alpha channel**, as the app's has none: the page encodes it itself, since a
    canvas' `toBlob` always writes one, and compresses it with the browser's `CompressionStream`,
    which Chrome 80, Firefox 113 and Safari 16.4 are the first to have.
- **Record video records the view as shown** until Stop recording, as the app records it: the
  images at the size they are composed, in the arrangement they had at the start, without captions
  or sound, at up to 30 frames a second.
  - **The browser saves it as `dog-<species>-<time>.mp4`**, or as `.webm` where it records no MP4:
    Chrome 154 records H.264 in MP4, Firefox 156 VP8 in WebM. A WebM a browser records states no
    duration, so a player may not seek in it.
  - **While it records, whatever would change the video's size is locked**: the source, the camera,
    *Side by side* and *Map of differences*.
  - **It stops when the page is hidden**, and the video recorded so far is saved.
  - **The images are rendered twice meanwhile**, once more in a WebGL context of the recording's
    own, since WebGL draws into its own canvas only.
  - **Firefox 156 on Android records no video of a file**: in the recording's context the video's
    frames come out magenta. The camera and a photo record there.
- **A large photo is scaled down to 1280 pixels by the browser**, with a 2D canvas at its highest
  smoothing, where the app decodes it at a power of two of the size and scales the rest. Both end
  at the same size, so the acuity blur is as wide, but the pixels it blurs can differ slightly.

### The command line

```sh
./gradlew :cli:uberJar           # cli/build/jars/dog-vision-cli.jar, with everything it needs
java -jar cli/build/jars/dog-vision-cli.jar --species cat --compare dog photo.jpg
./gradlew :cli:installJvmDist    # or a start script and its JARs, in cli/build/install/dog-vision-cli-jvm
```

- **It converts a photo at full size** through `core`, as the desktop program's
  `dog-vision photo.jpg` does: it writes `photo.dog.png` next to the photo, or into `--output-dir`, and says
  what share of the pixels differ when `--difference` asks for the map.
- **It takes the desktop program's options** for the view: `--species`, `--compare`,
  `--difference`, `--adaptation`, `--strength`, `--chroma-scale`, `--acuity` and `--fov`;
  `--help` lists them. `--info`, a video and the window are not in it.
- **A photo is turned as its EXIF orientation says**, as OpenCV turns it for the desktop program;
  the JDK's ImageIO, which decodes it, reads no EXIF, so the command line reads the orientation
  itself.
- **It speaks the system's language**, English or Czech, in `texts`' strings: the JVM takes it
  from `LC_ALL`, `LC_MESSAGES` or `LANG`, and not from `LANGUAGE`. The desktop program's command
  line speaks English only.

### The desktop window

```sh
./gradlew :desktop:run                                   # the camera, /dev/video0
./gradlew :desktop:run --args="--window photo.jpg"       # a photo, or a video played over and over
./gradlew :desktop:run --args="--species cat photo.jpg"  # converts it, as the command line does
./gradlew :desktop:packageUberJarForCurrentOS            # desktop/build/compose/jars/dog-vision-linux-x64-0.1.0.jar
./gradlew :desktop:windowsUberJar                        # desktop/build/compose/jars/dog-vision-windows-x64-0.1.0.jar
```

- **Each JAR holds everything the window needs**, the natives for its system on x86-64 included,
  and runs as `java -jar dog-vision-linux-x64-0.1.0.jar` with the same options.
- **The Windows JAR is built on any machine**, Linux included, with Windows's natives and ANGLE in
  place of this machine's.
- **Packages with a runtime of their own** are made as [The desktop packages](#the-desktop-packages)
  says.

It is a first version, for Linux and Windows on x86-64, of a window to replace the desktop
program's.

- **It takes the command line's options**, and converts a photo given without `--window` as the
  command line does.
- **The view is rendered on the GPU**, by the passes the app and the web page run, in a GL context
  with no window of its own, which is read back and shown in the Compose window.
  - **It is read back without waiting for the GPU**, through a pixel pack buffer and a fence, two
    at a time, so that the next frame is uploaded and composed while the GPU reads the last.
  - **On Linux it needs EGL with Mesa's device platform** (`EGL_EXT_platform_device`) for an
    OpenGL ES 3 context, and takes the first device with a DRM render node; with none, it renders
    on the CPU and says so on its standard error.
  - **On Windows it draws through ANGLE**, OpenGL ES 3 over Direct3D 11, as Chrome draws WebGL
    there. ANGLE's DLLs come in the JAR, from Nucleus's build of it
    (`dev.nucleusframework:nucleus.angle-natives`), as Google ships none.
  - **Where ANGLE cannot start, WGL draws**: the graphics driver's own desktop OpenGL 3.3, whose
    GLSL takes the passes' shaders with their first line rewritten. `--gl angle` or `--gl wgl`
    picks one alone.
- **A video or the camera comes through the system's `ffmpeg`**, which must be on the `PATH` with
  `ffprobe`: it decodes the frames, turns a video as its file says and scales it down to 1280
  pixels, and hands them over as raw RGBA through a pipe.
  - **Windows has no ffmpeg of its own**, so it has to be installed; where `ffmpeg` or `ffprobe`
    cannot run, the window says so in place of the video or the camera.
  - **`--camera N` is `/dev/videoN` on Linux**, through Video4Linux, and on Windows the Nth camera
    ffmpeg lists through DirectShow, counted from 0.
  - **The camera is asked for Motion-JPEG at 1280 x 720**, which a USB webcam gives at 30 frames a
    second, and for whatever it gives where it has none. It is not mirrored, as the desktop
    program's is not.
  - **A video plays at its own speed**, without its sound; an HDR video is not tone mapped.
- **A photo is scaled down to 1280 pixels** for the view, halved bilinearly and then scaled, as the
  app decodes it at a power of two of its size.
- **Open a photo or a video opens another one** from the system's dialog, which starts in the
  folder of the file shown; the o key opens the dialog too, as it opens the desktop program's, and
  a file dropped anywhere on the window opens as well. **Camera goes back to the camera** the
  command line names.
- **q or Escape closes it**, as it closes the desktop program's window.
- **It has no menus, no settings, no snapshots and no recording yet.**

### The desktop packages

```sh
./gradlew :desktop:packageDeb    # desktop/build/compose/binaries/main/deb/dog-vision_0.1.0_amd64.deb
./gradlew :desktop:packageRpm    # desktop/build/compose/binaries/main/rpm/dog-vision-0.1.0-1.x86_64.rpm
./gradlew :desktop:packageTarGz  # desktop/build/compose/binaries/main/tar/dog-vision-0.1.0-linux-x64.tar.gz
tools/package_msi_on_linux.sh --jdk <Windows JDK 17> --jmods <Windows JDK 25's jmods> --wix <WiX 3.14>
                                 # desktop/build/compose/binaries/main/msi/dog-vision-0.1.0.msi
```

Besides the build's own needs, making them takes these tools, on Ubuntu from the packages named:

| Package | Tools                                      | Ubuntu's packages | Run by                                           |
|---------|--------------------------------------------|-------------------|--------------------------------------------------|
| deb     | `dpkg-deb`, `dpkg`, `fakeroot`             | dpkg, fakeroot    | jpackage, and `packageDeb` to pack it again      |
| deb     | `readelf`                                  | binutils          | `debDepends`                                     |
| rpm     | `rpmbuild` 4.10 or later, `rpm`, `elfdeps` | rpm               | jpackage and `rpmLibraryRequires`                |
| MSI     | Wine, a Windows JDK 17, jmods, WiX 3.14    | wine, winetricks  | `tools/package_msi_on_linux.sh`, which says more |

- **Gradle downloads Temurin 25 on the first build** that needs it, where it finds none installed,
  so that build needs the network.
- **The tar.gz needs nothing more**, and neither do the JARs.

- **Each brings a runtime of its own**, Temurin's JDK 25, which jlink cuts down to the modules the
  window uses.
  - **Gradle downloads Temurin for the Linux packages** as a toolchain where the machine has none,
    through the Foojay resolver in `settings.gradle.kts`; the MSI's comes from the jmods given to
    its script.
  - **Temurin brings its own libjpeg, giflib, libpng, lcms2, HarfBuzz and FreeType**, which a
    distribution's OpenJDK, Ubuntu's among them, takes from the system; so the Linux packages need
    little more than glibc 2.17 or later, X11, ALSA, fontconfig and the C++ runtime.
- **Each has two launchers**:
  - `dog-vision`, the window, which converts a photo given alone as the command line does;
  - `dog-vision-cli`, the command line alone, which prints its usage rather than open the window,
    and on Windows runs in a console.
- **The deb and the rpm install into `/opt/dog-vision`**, with the launchers in its `bin`, and add
  the window to the desktop's menu under Graphics.
- **The tar.gz is the same application unpacked**, to run as `dog-vision/bin/dog-vision` without
  installing it.
- **The deb and the rpm depend on libEGL and ffmpeg** as well as on the libraries the image links
  against, since LWJGL opens libEGL only once it runs.
  - **The deb names packages as Ubuntu 20.04 and Debian 11 name them**, which later releases keep
    or provide (Ubuntu 24.04's `libasound2t64` provides `libasound2`), so that the deb is the same
    wherever it is built. The task `debDepends` reads the libraries the image needs from its ELF
    files and names each by the table `debianPackages` in
    [`desktop/build.gradle.kts`](desktop/build.gradle.kts), failing on one the table lacks;
    `packageDeb` then puts that Depends into jpackage's deb in place of the build machine's names
    and packs it again with xz, which every dpkg reads.
  - **The deb was tried on Ubuntu 20.04**, installed and its command line run, **and on Debian 11,
    Debian 12, Ubuntu 22.04 and 24.04**, where `apt-get install --simulate` resolved every
    dependency.
  - **The rpm names libraries and a file** (`libX11.so.6()(64bit)`, `libEGL.so.1()(64bit)`,
    `/usr/bin/ffmpeg` and the like), as Fedora and openSUSE name their packages differently. rpm's
    `elfdeps` lists the libraries from the image, in the task `rpmLibraryRequires`, as jpackage
    finds their packages only on an rpm-based build machine.

### Caveat: the deb does not install where the system has no desktop menu

jpackage's script adds the window to the desktop's menu with `xdg-desktop-menu`, which fails where
`/usr/share/applications` does not exist, as in a bare container, and dpkg then leaves the package
unconfigured.
- **The MSI is built on Linux under Wine**, as jpackage builds an installer only on the system it
  is for. It installs into `Program Files\dog-vision` and adds both launchers to the Start menu, in
  a dog-vision group, and to the desktop. It carries a fixed upgrade code, so that a later version's
  MSI replaces it; no second version has tried that yet.
  - **Wine needs three detours**, which
    [`tools/package_msi_on_linux.sh`](tools/package_msi_on_linux.sh) describes and takes: jpackage from a
    JDK 17 with the runtime linked on Linux, WiX's light run a second time without validating the
    MSI, and Microsoft's .NET Framework 4.8 in the prefix.
  - **The MSI's code page is Windows-1250**, from
    [`desktop/packaging/windows`](desktop/packaging/windows/MsiInstallerCodepage_en.wxl), as the
    vendor's name has a ř that jpackage's Windows-1252 lacks.
  - **The Start menu and the desktop get `dog-vision-cli` as well**, which started from there only
    prints its usage: JDK 17's jpackage cannot leave one launcher out.

### Caveat: the Windows window is tried under Wine only

It has not run on a Windows machine yet, only under Wine 11.18 on Linux, with a Windows JDK 21 and
the Windows build of ffmpeg: a photo, a video, the camera through DirectShow, WGL chosen and WGL
taken where Direct3D 11 is switched off, and the command line.

- **Wine's own `d3dcompiler_47` never finishes linking one of the passes' shaders**, the count of
  differing pixels, so ANGLE hangs there under Wine; Microsoft's, which Windows ships and
  `winetricks d3dcompiler_47` installs into a Wine prefix, links it.
- **ANGLE's Direct3D 11 runs on Wine's translation to OpenGL** there, not on a Windows driver, so
  what Wine shows is the code and ANGLE, not the drivers people have.
- **The MSI was installed and removed under Wine too**, and its window played a video and its
  command line wrote the same PNG as Linux's.
- **Installing it under Wine writes into the home**: Wine turns the Start menu's and the desktop's
  shortcuts into the Linux desktop's (`~/.local/share/applications/wine`, `~/Desktop`) unless
  `WINEDLLOVERRIDES=winemenubuilder.exe=d` is set, and the prefix's Desktop, Documents and other
  folders link into the home unless `winetricks sandbox` removed the links.

### Caveat: the libraries wait for a stable SDK 37

The Compose BOM stays at 2026.06.01, `androidx.core` at 1.18 and `lifecycle` at 2.10, and the app
targets API 36: the releases after them need `compileSdk` 37, which has no stable SDK platform yet.
Android Lint says so in its only warnings.

## How the code is laid out

- **[`core`](core)** is plain Kotlin with no platform in it: the species, the model, the image
  pipeline, the facts and the layout of the images.
  They are what the desktop program's `dog_vision.core` holds, less video and the window's session.
  - It is built by Kotlin Multiplatform for the JVM, which the Android app runs it on, and for
    JavaScript.
  - The one part written for each of them is how a fact's number is rounded, in
    [`Facts.jvm.kt`](core/src/jvmMain/kotlin/cz/loplex/dogvision/core/Facts.jvm.kt) and
    [`Facts.js.kt`](core/src/jsMain/kotlin/cz/loplex/dogvision/core/Facts.js.kt), since JavaScript
    has no `BigDecimal`.
- **[`gl`](gl)** holds the GLSL ES 3.00 programs that render a view, built alike for the JVM and
  JavaScript: OpenGL ES 3.0 runs them on Android, and WebGL 2 runs GLSL ES 3.00 as it is.
  - [`ViewPasses`](gl/src/commonMain/kotlin/cz/loplex/dogvision/gl/ViewPasses.kt) runs them in
    order through [`Gl`](gl/src/commonMain/kotlin/cz/loplex/dogvision/gl/Gl.kt), the part of
    OpenGL ES 3.0 they need, which WebGL 2 has as well.
  - `Gl` names GL objects by integers, as OpenGL ES does; over WebGL 2, whose objects are
    JavaScript objects, a table holds them, as Emscripten's GL layer does.
- **[`texts`](texts)** words what the app, the web page, the desktop window and the command line
  say, in English and Czech, with no
  toolkit in it, built alike for the JVM and JavaScript.
  - Its strings are in its [`strings`](texts/strings) folder, in Android's `strings.xml` format,
    which the build compiles into Kotlin, each named by an entry of the enum `Str` or `Plural`.
  - [`Texts`](texts/src/commonMain/kotlin/cz/loplex/dogvision/texts/Texts.kt) gives them in one
    language, with English for a string the language lacks, as Android does, and words a species,
    the facts about it and the images' captions.
  - The JVM has no public plural rules, so English and Czech have theirs in
    [`Texts.jvm.kt`](texts/src/jvmMain/kotlin/cz/loplex/dogvision/texts/Texts.jvm.kt); JavaScript
    takes them from `Intl`.
- **[`ui`](ui)** holds the controls of the view in Compose Multiplatform, for Android and the JVM:
  [`Controls`](ui/src/commonMain/kotlin/cz/loplex/dogvision/ui/Controls.kt), its sections, sliders
  and choices, the selected species' facts, and what each control means.
  - They take `texts`' strings through `LocalTexts`, which whoever shows them provides.
  - Compose Multiplatform 1.11 is Jetpack Compose 1.11, and its Material 3 1.9 is androidx
    Material 3 1.4, the versions of the app's Compose BOM, so that the app runs one of each.
- **[`app`](app)** is the Android app: the camera, the photos and videos, the GPU renderer, saving,
  recording, and the Compose screens around `ui`'s controls.
  - It provides `LocalTexts` in the language of the activity's configuration, which the language
    choice sets as it would for resources.
  - Its one string resource, the name under the launcher's icon, is written by the build from
    `texts`' `app_name`, since the manifest takes a name only from a resource.
- **[`web`](web)** is a page for a browser, in Kotlin/JS: a photo, a video or the camera shown with
  the app's controls and the selected species' facts.
  - [`Passes`](web/src/jsMain/kotlin/cz/loplex/dogvision/web/Passes.kt) renders the view with
    `gl`'s `ViewPasses` over [`WebGl`](web/src/jsMain/kotlin/cz/loplex/dogvision/web/WebGl.kt),
    its `Gl` for WebGL 2.
  - Its wording is `texts`', in the language chosen at the end of its panel, which the browser
    remembers, or else in the browser's language if there are strings for it and in English
    otherwise.

- **[`cli`](cli)** is the command line on the JVM, with nothing but `core`, `texts` and the JDK
  under it:
  [`Arguments`](cli/src/jvmMain/kotlin/cz/loplex/dogvision/cli/Arguments.kt) reads the desktop
  program's options, and
  [`Conversion`](cli/src/jvmMain/kotlin/cz/loplex/dogvision/cli/Conversion.kt) converts a photo.
  - [`runCommandLine`](cli/src/jvmMain/kotlin/cz/loplex/dogvision/cli/Main.kt) hands whatever is
    no conversion to a window given to it, so that a desktop window can take the same command line.

- **[`desktop`](desktop)** is the desktop window, in Compose Multiplatform for the JVM, around
  `ui`'s controls; its `main` is `cli`'s, with the window as the part that is no conversion.
  - [`Renderer`](desktop/src/jvmMain/kotlin/cz/loplex/dogvision/desktop/Renderer.kt) runs `gl`'s
    `ViewPasses` on a thread of its own, in the context
    [`GlContext`](desktop/src/jvmMain/kotlin/cz/loplex/dogvision/desktop/GlContext.kt) opens for
    the system, through the `Gl` that comes with it.
  - [`EglContext`](desktop/src/jvmMain/kotlin/cz/loplex/dogvision/desktop/EglContext.kt) makes
    an OpenGL ES context on Linux's device platform or ANGLE's display, drawn in through
    [`LwjglGles`](desktop/src/jvmMain/kotlin/cz/loplex/dogvision/desktop/LwjglGles.kt), over
    LWJGL's OpenGL ES bindings.
  - [`WglContext`](desktop/src/jvmMain/kotlin/cz/loplex/dogvision/desktop/WglContext.kt) makes
    Windows's desktop OpenGL context through GLFW, drawn in through
    [`LwjglGl`](desktop/src/jvmMain/kotlin/cz/loplex/dogvision/desktop/LwjglGl.kt), over LWJGL's
    desktop OpenGL bindings.
  - [`FfmpegFeed`](desktop/src/jvmMain/kotlin/cz/loplex/dogvision/desktop/FfmpegFeed.kt) reads a
    video's or the camera's frames from `ffmpeg`.

- **[`testing`](testing)** is what the renderers' tests hold them to, for `core`'s tests, the app's
  instrumented ones, the web page's and the desktop window's alike:
  [`References`](testing/src/commonMain/kotlin/cz/loplex/dogvision/testing/References.kt) draws the
  reference script's pattern, renders it with `core`, and says how far apart two images are.

### The view is rendered on the GPU, and a photo at full size on the CPU

- **The view is drawn by OpenGL ES 3.0 shaders**, `gl`'s
  [`Shaders`](gl/src/commonMain/kotlin/cz/loplex/dogvision/gl/Shaders.kt), in
  `gl`'s [`ViewPasses`](gl/src/commonMain/kotlin/cz/loplex/dogvision/gl/ViewPasses.kt), which apply
  `core`'s matrix and blur, through the app's
  [`Gles`](app/src/main/kotlin/cz/loplex/dogvision/render/Gles.kt).
  They decode and encode sRGB through `core`'s own lookup tables, so that they agree with it to the
  rounding of a float.
- **The same passes draw every view there is**:
  - the screen, through
    [`ViewRenderer`](app/src/main/kotlin/cz/loplex/dogvision/render/ViewRenderer.kt);
  - a recording, which the renderer draws into an encoder's surface as well;
  - a video converted at full size, through the Media3 effect
    [`ViewEffect`](app/src/main/kotlin/cz/loplex/dogvision/video/ViewEffect.kt).
- **A video's frames reach the renderer as the camera's do**:
  [`VideoFeed`](app/src/main/kotlin/cz/loplex/dogvision/video/VideoFeed.kt) plays it with ExoPlayer
  and hands each frame over scaled down.
- **A photo saved at full size goes through `core`'s CPU pipeline**, a strip of rows at a time,
  which has no limit on the size of a texture.

## Checking it

- `./gradlew check` runs everything below that needs no phone: the model's tests, the web page's,
  Android Lint, and the Kotlin style.
- `./gradlew :core:jvmTest` runs the model's tests on the JVM: the desktop program's invariants,
  ported from its pytest suite, and a comparison with what the desktop program computes.
- `./gradlew :core:allTests` runs them, and in Node.js as well the tests in
  [`commonTest`](core/src/commonTest/kotlin/cz/loplex/dogvision/core), which hold the JVM and
  JavaScript to rounding a fact's number and naming a snapshot alike.
- `./gradlew :texts:allTests` runs the tests of the wording on the JVM and in Node.js:
  [`TextsTest`](texts/src/commonTest/kotlin/cz/loplex/dogvision/texts/TextsTest.kt) holds every
  language to having every string, as Android Lint's `MissingTranslation` would, and a string, a
  plural and a decimal separator to what Android gives.
- `./gradlew :ui:jvmTest` runs the shared controls' tests on the JVM, in Compose's test scene:
  [`ControlsTest`](ui/src/jvmTest/kotlin/cz/loplex/dogvision/ui/ControlsTest.kt) holds which
  sections start open, the facts to the selected species, a slider to whole percent, the controls
  a recording locks, the language choice and an info button's dialog.
- `./gradlew :cli:jvmTest` runs the command line's tests: its options, the EXIF orientation in
  either byte order and each turn it asks for, and a photo converted exactly as `core` composes it.
- `./gradlew :desktop:jvmTest` runs the window's tests, on the machine's GPU through EGL and with
  its `ffmpeg`:
  - [`PassesTest`](desktop/src/jvmTest/kotlin/cz/loplex/dogvision/desktop/PassesTest.kt) holds
    the passes to `core`'s CPU pipeline, as the page's and the app's tests do, and the area read
    back to its top row first, over OpenGL ES 3.0 and over desktop OpenGL 3.3, as WGL draws, each
    in a JVM of its own.
  - [`FfmpegFeedTest`](desktop/src/jvmTest/kotlin/cz/loplex/dogvision/desktop/FfmpegFeedTest.kt)
    plays a video it makes, turned by its file, upright and over and over; reads ffmpeg's list of
    DirectShow cameras as ffmpeg words it now and as it did before 4.4; and holds a program that
    cannot run to being said to be missing, and one that runs to reading nothing.
- `./gradlew :web:jsTest` runs the page's tests in headless Chrome:
  - [`PassesTest`](web/src/jsTest/kotlin/cz/loplex/dogvision/web/PassesTest.kt) holds the page's
    WebGL 2 passes to `core`'s CPU pipeline, as `ViewRendererTest` below holds the app's; the share
    of differing pixels counted without waiting, as the camera's is, to the share counted at once;
    and a video's frame, as the camera's are, to its canvas, as it is and mirrored, and scaled down
    as a photo is when it is larger, or as its file stores it, scaled and turned by the passes.
  - [`Mp4Test`](web/src/jsTest/kotlin/cz/loplex/dogvision/web/Mp4Test.kt) reads each quarter turn
    of an MP4's video track, with the movie box after the media as a phone writes it.
    Chrome renders WebGL 2 there in software, with SwiftShader, as
    [`karma.config.d/webgl.js`](web/karma.config.d/webgl.js) tells it to, so it does not test the
    machine's GPU.
    `./gradlew :web:jsTest -PwebTestsOnGpu` runs them on the GPU instead, through ANGLE on Vulkan,
    which needs a GPU and a driver Chrome can use Vulkan with.
  - [`GlConstantsTest`](web/src/jsTest/kotlin/cz/loplex/dogvision/web/GlConstantsTest.kt) holds
    `gl`'s values of the GL enums to WebGL 2's.
  - [`SavingTest`](web/src/jsTest/kotlin/cz/loplex/dogvision/web/SavingTest.kt) holds how a
    snapshot puts the images together.
  - [`PngTest`](web/src/jsTest/kotlin/cz/loplex/dogvision/web/PngTest.kt) holds the snapshot's
    PNG to truecolour without alpha, and has the browser decode it, under each filter, to the same
    pixels.
  - [`RecordingTest`](web/src/jsTest/kotlin/cz/loplex/dogvision/web/RecordingTest.kt) records a
    photo's view and plays the video back, at the size of its images side by side.
- `./gradlew :app:connectedDebugAndroidTest` runs the instrumented tests on a connected phone or
  emulator, on that device's GPU and codecs:
  - [`ViewRendererTest`](app/src/androidTest/kotlin/cz/loplex/dogvision/render/ViewRendererTest.kt)
    holds the GPU renderer to `core`'s CPU pipeline;
  - [`RecordingTest`](app/src/androidTest/kotlin/cz/loplex/dogvision/render/RecordingTest.kt)
    records the view and checks the video's size, frame times and colours;
  - [`VideoFeedTest`](app/src/androidTest/kotlin/cz/loplex/dogvision/video/VideoFeedTest.kt) plays a
    video into the renderer's frames;
  - [`ConversionTest`](app/src/androidTest/kotlin/cz/loplex/dogvision/video/ConversionTest.kt)
    converts a video and holds it to `core`'s CPU pipeline;
  - [`PhotosTest`](app/src/androidTest/kotlin/cz/loplex/dogvision/PhotosTest.kt) holds a photo's
    semi-transparent pixel to its colour as stored, as the web page takes it;
  - [`SavedViewTest`](app/src/androidTest/kotlin/cz/loplex/dogvision/SavedViewTest.kt) and
    [`SavedSourceTest`](app/src/androidTest/kotlin/cz/loplex/dogvision/SavedSourceTest.kt) bring the
    controls and the source shown back through a Parcel, as when Android ends the app in the
    background.
- `./gradlew :app:lintDebug` runs Android Lint.
- **The Kotlin style is [ktlint](https://pinterest.github.io/ktlint/)'s, as IntelliJ IDEA formats
  Kotlin**, set in [`.editorconfig`](.editorconfig):
  - `./gradlew ktlintCheck` checks it, and `./gradlew ktlintFormat` fixes what it can;
  - `./gradlew checkLineLength` holds every line to 120 characters, comments included: ktlint does
    not measure a line that is a comment and nothing else.
- `python3 tools/check_links.py` checks that every relative link in the Markdown resolves, down to
  its anchor.

### Caveat: the emulator fails the video tests' colour checks

On the emulator, a BT.709 video comes out as BT.601's matrix decodes it, so a red of 200 comes out
as 186 there, and `VideoFeedTest` and `ConversionTest` fail their colour checks.

- **It goes with the decoder, as far as has been seen, not with the GPU**: the emulator decodes with
  its own `c2.goldfish.h264.decoder`, and on a Redmi Note 13 Pro 5G, Android's software decoder
  `c2.android.avc.decoder` gives the same colours on the phone's Adreno 710.
- **Qualcomm's hardware decoder gives the colours as tagged**, and the tests pass on that phone.
- **The one test that plays a video through the software decoder there checks its size only**: it
  plays a video of 96 x 64, which the hardware decoder refuses.

### The test videos come from a script

[`tools/make_test_videos.sh`](tools/make_test_videos.sh) writes the videos in
[`app/src/androidTest/assets`](app/src/androidTest/assets) with ffmpeg: four flat quadrants, one of
them turned as a phone held upright records, and one too small for Qualcomm's hardware decoder.
With the same ffmpeg, it writes the same bytes each time it runs.

### The reference values come from the desktop program

[`tools/reference_values.py`](tools/reference_values.py) writes the files in
[`core/src/jvmTest/resources`](core/src/jvmTest/resources) from a checkout of the desktop program:
its matrices, RNL factors and neutral points, images it renders from a test pattern, and the facts
of every species.
Its docstring says how to run it; the tests fail when `core` stops matching them.

## License

This port of dog-vision is free software under the GNU General Public License, version 3 or any
later version (`GPL-3.0-or-later`), as the desktop program is; the full text is in
[`LICENSE`](LICENSE).
