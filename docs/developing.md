# Developing dog-vision

How the code is laid out, and how a change is checked.
Building the programs is [Building it](building.md)'s.

- [How the code is laid out](#how-the-code-is-laid-out) — the modules, and the GPU renderer they
  share.
- [Checking it](#checking-it) — what each Gradle task tests, the Kotlin style, and what GitHub
  Actions runs.
- The caveats: [the emulator's colours](#caveat-the-emulator-fails-the-video-tests-colour-checks),
  [Windows without a GPU and under Wine](#caveat-windows-is-tested-without-a-gpu-and-under-wine),
  and [the libraries waiting for SDK 37](#caveat-the-libraries-wait-for-a-stable-sdk-37).
- [Where the test data comes from](#where-the-test-data-comes-from) — the test videos and the
  reference values.

## How the code is laid out

| Module                            | Holds                                      | Built for           |
|-----------------------------------|--------------------------------------------|---------------------|
| [`core`](../core)                 | the species, the model, the image pipeline | the JVM, JavaScript |
| [`gl`](../gl)                     | the GLSL ES 3.00 passes that render a view | the JVM, JavaScript |
| [`texts`](../texts)               | the wording, in English and Czech          | the JVM, JavaScript |
| [`ui`](../ui)                     | the controls, in Compose Multiplatform     | Android, the JVM    |
| [`app`](../app)                   | the Android app                            | Android             |
| [`web`](../web)                   | the web page                               | JavaScript          |
| [`cli`](../cli)                   | the command line                           | the JVM             |
| [`desktop-core`](../desktop-core) | what the desktop windows share             | the JVM             |
| [`desktop`](../desktop)           | the desktop window, in Compose             | the JVM             |
| [`swing`](../swing)               | the desktop window, in Swing               | the JVM             |
| [`testing`](../testing)           | what the renderers' tests hold them to     | the JVM, JavaScript |

[`build-logic`](../build-logic) is no module but a Gradle build of its own, included in this one:
the tasks the Linux packages are made with, which a module takes by applying the plugin
`cz.loplex.dogvision.packaging`.

- **`core` has no platform in it**: it holds what the desktop program's `dog_vision.core` holds,
  less video and the window's session.
  The one part written for each platform is how a fact's number is rounded, in
  [`Facts.jvm.kt`](../core/src/jvmMain/kotlin/cz/loplex/dogvision/core/Facts.jvm.kt) and
  [`Facts.js.kt`](../core/src/jsMain/kotlin/cz/loplex/dogvision/core/Facts.js.kt), since JavaScript
  has no `BigDecimal`.
- **`gl`'s passes run on every GPU the programs draw on**: OpenGL ES 3.0 on Android, WebGL 2 in the
  browser, and EGL, ANGLE or WGL on the desktop.
  [`ViewPasses`](../gl/src/commonMain/kotlin/cz/loplex/dogvision/gl/ViewPasses.kt) runs them
  through [`Gl`](../gl/src/commonMain/kotlin/cz/loplex/dogvision/gl/Gl.kt), the part of OpenGL ES
  3.0 they need, which each program implements.
- **`texts`' strings are in [`texts/strings`](../texts/strings)**, in Android's `strings.xml`
  format, which the build compiles into Kotlin, each named by an entry of the enum `Str` or
  `Plural`.
  The app, the web page, the window and the command line all speak through it.
- **`ui`'s Compose Multiplatform 1.11 is Jetpack Compose 1.11**, and its Material 3 1.9 is androidx
  Material 3 1.4, the versions of the app's Compose BOM, so that the app runs one of each.
- **`cli`'s `main` is the window's too**:
  [`runCommandLine`](../cli/src/jvmMain/kotlin/cz/loplex/dogvision/cli/Main.kt) hands whatever is
  no conversion to a window given to it.
- **`desktop-core` has no toolkit in it**, neither Compose nor skiko: the GL contexts, the passes'
  renderer, ffmpeg's feeds, and the pixels read back handed to a function that makes the window's
  image of them.
  Its `LiveSession` holds what a window shows, as the Python program's does, so that the Compose
  window and the Swing one only lay it out.

### The view is rendered on the GPU, and a photo at full size on the CPU

- **The view is drawn by `gl`'s shaders**, which apply `core`'s matrix and blur, and decode and
  encode sRGB through `core`'s own lookup tables, so that they agree with it to the rounding of a
  float.
- **In the app, the same passes draw every view there is**: the screen, a recording, which the
  renderer draws into an encoder's surface as well, and a video converted at full size, through the
  Media3 effect [`ViewEffect`](../app/src/main/kotlin/cz/loplex/dogvision/video/ViewEffect.kt).
- **The desktop window renders in a GL context with no window of its own**, and reads the view back
  into the Compose window without waiting for the GPU.
- **A photo saved at full size goes through `core`'s CPU pipeline**, a strip of rows at a time,
  which has no limit on the size of a texture.

## Checking it

`./gradlew check` runs every test task below that needs no phone, Android Lint, and the Kotlin
style.
The test classes' comments say what each of them holds.

| Task                                        | Tests                                                |
|---------------------------------------------|------------------------------------------------------|
| `./gradlew :core:jvmTest`                   | the model, against the desktop program's values      |
| `./gradlew :core:allTests`                  | the same, and the JVM and Node.js agreeing           |
| `./gradlew :texts:allTests`                 | every language having every string, and plurals      |
| `./gradlew :ui:jvmTest`                     | the shared controls, in Compose's test scene         |
| `./gradlew :cli:jvmTest`                    | the options, the EXIF orientation, a conversion      |
| `./gradlew :desktop-core:jvmTest`           | the window's passes, GL contexts, ffmpeg, session    |
| `./gradlew :swing:jvmTest`                  | the Swing window's image, theme and a dropped file   |
| `./gradlew :web:jsTest`                     | the page's passes, snapshot and recording            |
| `./gradlew :app:connectedDebugAndroidTest`  | the renderer, recording and conversion, on a device  |
| `./gradlew :app:lintDebug`                  | Android Lint alone                                   |

- **The renderers' tests hold each GPU renderer to `core`'s CPU pipeline**: the app's, the web
  page's and the window's alike, through [`testing`](../testing)'s reference pattern.
- **`:desktop-core:jvmTest` draws on this machine's GPU**, through EGL on Linux and through ANGLE and WGL
  on Windows, and runs the machine's `ffmpeg`.
- **`:web:jsTest` runs in headless Chrome, which renders WebGL 2 in software**, with SwiftShader, as
  [`karma.config.d/webgl.js`](../web/karma.config.d/webgl.js) tells it to.
  `./gradlew :web:jsTest -PwebTestsOnGpu` runs the tests on the GPU instead, through ANGLE on Vulkan.
- **`:app:connectedDebugAndroidTest` runs on the connected phone or emulator**, on its GPU and
  codecs.
- **`python3 tools/check_links.py` checks the Markdown**, which `check` does not: every relative
  link resolves, down to its anchor.

### The Kotlin style is ktlint's

The style is [ktlint](https://pinterest.github.io/ktlint/)'s, as IntelliJ IDEA formats Kotlin, set
in [`.editorconfig`](../.editorconfig):

- `./gradlew ktlintCheck` checks it, and `./gradlew ktlintFormat` fixes what it can;
  `build-logic`'s own are `:build-logic:ktlintCheck` and `:build-logic:ktlintFormat`, and each
  module's `check` runs `build-logic`'s;
- `./gradlew checkLineLength` holds every line to 120 characters, comments included: ktlint does not
  measure a line that is a comment and nothing else.

### GitHub Actions runs them on Linux and Windows

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs on every push:

- **On Ubuntu 24.04, `./gradlew check`**, the window's GL tests on Mesa's llvmpipe, as the runner
  has no GPU; then the debs and the rpms of `dog-vision` and `dog-vision-cli`, each built in two
  versions and tried by `test_deb.sh --upgrade` in Ubuntu 20.04 and `test_rpm.sh --upgrade` in
  Fedora 42, `dog-vision`'s window under Xvfb; and the tar.gz, unpacked on the runner, where its
  command line runs and its window's main converts a photo.
- **On Windows Server 2022, `./gradlew :desktop-core:jvmTest`**, over ANGLE on WARP and over WGL on
  Mesa's llvmpipe, which the job puts beside `java.exe`, as Windows's own OpenGL is 1.1.
- **On Windows Server 2022, the MSI**, built by `package_msi_on_windows.ps1` in two versions and
  tried by `test_msi_on_windows.ps1`; the MSIs are the run's artifacts.

[`.github/workflows/msi-under-wine.yml`](../.github/workflows/msi-under-wine.yml) runs when started
by hand, as it installs Wine and .NET Framework 4.8 each time: the MSI that
`package_msi_on_linux.sh` builds under Wine, validated by `smoke.exe` and tried by
`test_msi_on_windows.ps1` on Windows Server 2022.

## Caveat: the emulator fails the video tests' colour checks

On the emulator, a BT.709 video comes out as BT.601's matrix decodes it, so a red of 200 comes out
as 186 there, and `VideoFeedTest` and `ConversionTest` fail their colour checks.

- **It goes with the decoder, as far as has been seen, not with the GPU**: the emulator decodes with
  its own `c2.goldfish.h264.decoder`, and on a Redmi Note 13 Pro 5G, Android's software decoder
  `c2.android.avc.decoder` gives the same colours on the phone's Adreno 710.
- **Qualcomm's hardware decoder gives the colours as tagged**, and the tests pass on that phone.
- **The one test that plays a video through the software decoder there checks its size only**: it
  plays a video of 96 x 64, which the hardware decoder refuses.

## Caveat: Windows is tested without a GPU and under Wine

The window has not run on a Windows machine yet, only under Wine 11.18 on Linux, with a Windows JDK
21 and the Windows build of ffmpeg: a photo, a video, the camera through DirectShow, WGL chosen and
WGL taken where Direct3D 11 is switched off, and the command line.

- **Its tests and its MSI run on GitHub's Windows Server 2022**, as
  [GitHub Actions runs them](#github-actions-runs-them-on-linux-and-windows), which has no GPU:
  ANGLE draws there on Direct3D 11's software rasteriser, WARP, and WGL on Mesa's llvmpipe.
- **ANGLE's Direct3D 11 runs on Wine's translation to OpenGL** under Wine, not on a Windows driver,
  so what Wine shows is the code and ANGLE, not the drivers people have.
- **Wine's own `d3dcompiler_47` never finishes linking one of the passes' shaders**, so ANGLE hangs
  under it; Microsoft's, which `winetricks d3dcompiler_47` installs into a Wine prefix, links it.
- **Installing the MSI under Wine writes into the home**: Wine turns its shortcuts into the Linux
  desktop's unless `WINEDLLOVERRIDES=winemenubuilder.exe=d` is set, and the prefix's folders link
  into the home unless `winetricks sandbox` removed the links.
- **The window's button that installs ffmpeg has run on Linux alone**, with scripts in place of
  winget and PowerShell, as Wine has neither winget nor a registry that winget writes to.
  winget's install itself ran on GitHub's Windows Server 2025, whose image has winget.

## Caveat: the libraries wait for a stable SDK 37

The Compose BOM stays at 2026.06.01, `androidx.core` at 1.18 and `lifecycle` at 2.10, and the app
targets API 36: the releases after them need `compileSdk` 37, which has no stable SDK platform yet.
Android Lint says so in its only warnings.

## Where the test data comes from

- **[`tools/make_test_videos.sh`](../tools/make_test_videos.sh) writes the videos** in
  [`app/src/androidTest/assets`](../app/src/androidTest/assets) with ffmpeg: four flat quadrants,
  one of them turned as a phone held upright records, and one too small for Qualcomm's hardware
  decoder.
  With the same ffmpeg, it writes the same bytes each time it runs.
- **[`tools/reference_values.py`](../tools/reference_values.py) writes the reference values** in
  [`core/src/jvmTest/resources`](../core/src/jvmTest/resources) from a checkout of the desktop
  program: its matrices, RNL factors and neutral points, images it renders from a test pattern, and
  the facts of every species.
  Its docstring says how to run it; the tests fail when `core` stops matching them.
