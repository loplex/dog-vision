# dog-vision

*A camera, a photo or a video, shown with the colours a dog — or another animal — can tell apart.*

![Red and yellow-green apples as they are, and as a dog, a cat, a protanope, a deuteranope and a harbour seal see them:
the red and the yellow-green merge for every dichromat, and all is grey for the seal](docs/images/apples-species.png)

This is a Kotlin port of [dog-vision](https://github.com/loplex/dog-vision-python), a desktop program in Python.\
[The model](docs/model.md) explains how it simulates each species, with its measurements and their sources.

## Four ways to run it

| Program                                                | Runs on                              | Shows                          |
|--------------------------------------------------------|--------------------------------------|--------------------------------|
| [The Android app](docs/using.md#the-android-app)       | Android 8.0 or later, OpenGL ES 3.0  | the camera, a photo or a video |
| [The web page](docs/using.md#the-web-page)             | a browser with WebGL 2               | the camera, a photo or a video |
| [The command line](docs/using.md#the-command-line)     | a JVM, 17 or newer; ffmpeg for video | a photo or a video, converted  |
| [The desktop window](docs/using.md#the-desktop-window) | Linux and Windows on x86-64          | the camera, a photo or a video |

- **The app, the web page and the window draw the view with the same GPU passes**, and the same controls,
  so each shows what the others do.
- **The desktop window is a first version**: it has no recording yet.
- **The desktop window comes in two toolkits**:
  `dog-vision-compose` in Compose, with the app's controls, and [`dog-vision-swing`](docs/using.md#the-same-window-in-swing-dog-vision-swing) in Swing,
  which shows the same from the same state and needs neither Compose nor skiko.
- **Each is built from this repository**, as [Building it](docs/building.md) says:

```sh
./gradlew :android:installDebug          # the app, onto the connected phone or emulator
./gradlew :web:jsBrowserDevelopmentRun   # the web page, served locally
./gradlew :gui-compose:run               # the Compose window, on the camera
./gradlew :gui-swing:runJvm              # the same window in Swing
```

## The documents

- [Using it](docs/using.md) — the app's controls, saving and recording; what the web page, the command line and the window do.
- [The model](docs/model.md) — every species and its sources, how the simulation works,
  the colour saturation, the acuity blur, the map of differences, and what it cannot show.
- [Building it](docs/building.md) — the JDK and the Android SDK, `./gradlew` for each program,
  and the deb, the rpm, the tar.gz, the MSI and the command line's zip for Windows.
- [Developing it](docs/developing.md) —
  the modules, what each test task holds, the CI, and the caveats of testing on an emulator and under Wine.

## Credits

The apple photo in the figures is [*Shiny red apples*](https://commons.wikimedia.org/wiki/File:Shiny_red_apples.jpg) by Leon Brooks, released into the public domain.

## License

This port of dog-vision is free software under the GNU General Public License,
version 3 or any later version (`GPL-3.0-or-later`); the full text is in [`LICENSE`](LICENSE).
