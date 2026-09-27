# dog-vision

*A camera, a photo or a video, shown with the colours a dog — or another animal — can tell
apart.*

This is a Kotlin port of [dog-vision](https://github.com/loplex/dog-vision-python), a desktop program in
Python.
It simulates the same species with the same model, and the desktop program's README explains that
model and the science behind it.

## Four ways to run it

| Program                                                | Runs on                              | Shows                          |
|--------------------------------------------------------|--------------------------------------|--------------------------------|
| [The Android app](docs/using.md#the-android-app)       | Android 8.0 or later, OpenGL ES 3.0  | the camera, a photo or a video |
| [The web page](docs/using.md#the-web-page)             | a browser with WebGL 2               | the camera, a photo or a video |
| [The command line](docs/using.md#the-command-line)     | a JVM, 17 or newer                   | a photo, converted to a file   |
| [The desktop window](docs/using.md#the-desktop-window) | Linux and Windows on x86-64          | the camera, a photo or a video |

- **The app, the web page and the window draw the view with the same GPU passes**, and the same
  controls, so each shows what the others do.
- **The desktop window is a first version** of a window to replace the desktop program's: it has no
  snapshots and no recording yet.
- **The desktop window comes in two toolkits**: `dog-vision` in Compose, with the app's controls,
  and [`dog-vision-swing`](docs/using.md#the-same-window-in-swing-dog-vision-swing) in Swing, which
  shows the same from the same state and needs neither Compose nor skiko.
- **Each is built from this repository**, as [Building it](docs/building.md) says:

```sh
./gradlew :app:installDebug              # the app, onto the connected phone or emulator
./gradlew :web:jsBrowserDevelopmentRun   # the web page, served locally
./gradlew :desktop:run                   # the desktop window, on the camera
./gradlew :swing:runJvm                  # the same window in Swing
```

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

## The documents

- [Using it](docs/using.md) — the app's controls, saving and recording, how it differs from the
  desktop program; what the web page, the command line and the window do.
- [Building it](docs/building.md) — the JDK and the Android SDK, `./gradlew` for each program, and
  the deb, the rpm, the tar.gz and the MSI.
- [Developing it](docs/developing.md) — the modules, what each test task holds, the CI, and the
  caveats of testing on an emulator and under Wine.

## License

This port of dog-vision is free software under the GNU General Public License, version 3 or any
later version (`GPL-3.0-or-later`), as the desktop program is; the full text is in
[`LICENSE`](LICENSE).
