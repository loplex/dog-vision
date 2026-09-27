# Building dog-vision

How to build each program from this repository, and the desktop window's packages.
What each program does is [Using it](using.md)'s.

- [What the build needs](#what-the-build-needs) — the JDK, the Android SDK, Chrome.
- [The Android app](#the-android-app), [the web page](#the-web-page),
  [the command line](#the-command-line) and [the desktop window](#the-desktop-window) — the
  `./gradlew` tasks and what they write.
- [The desktop packages](#the-desktop-packages) — the deb, the rpm, the tar.gz and the MSI: the
  tools they need, what they install, their dependencies, and the scripts that try them.

## What the build needs

- **A JDK 17 or newer**; Gradle itself is fetched by the wrapper, and Node.js, which runs `core`'s
  tests in JavaScript, by the Kotlin Gradle plugin.
- **The Android SDK with platform 36**, found through `ANDROID_HOME` or `sdk.dir` in
  `local.properties`, which Android Studio and IntelliJ IDEA write when they open the project.
- **Google Chrome** for the web page's tests, found on the `PATH` as `google-chrome` or through
  `CHROME_BIN`.

## The Android app

```sh
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug      # onto the connected phone or emulator
```

The phone needs Android 8.0 (API 26) or later, and OpenGL ES 3.0.

## The web page

```sh
./gradlew :web:jsBrowserDevelopmentRun   # serves the page, and builds it again on every change
./gradlew :web:jsBrowserDistribution     # web/build/dist/js/productionExecutable
```

## The command line

```sh
./gradlew :cli:uberJar           # cli/build/jars/dog-vision-cli.jar, with everything it needs
./gradlew :cli:installJvmDist    # or a start script and its JARs, in cli/build/install/dog-vision-cli-jvm
```

## The desktop window

```sh
./gradlew :desktop:run                                   # the camera, /dev/video0
./gradlew :desktop:run --args="--window photo.jpg"       # a photo, or a video played over and over
./gradlew :desktop:packageUberJarForCurrentOS            # desktop/build/compose/jars/dog-vision-linux-x64-0.1.0.jar
./gradlew :desktop:windowsUberJar                        # desktop/build/compose/jars/dog-vision-windows-x64-0.1.0.jar
```

- **Each JAR holds everything the window needs**, the natives for its system on x86-64 included,
  and runs as `java -jar dog-vision-linux-x64-0.1.0.jar` on a JDK 17 or newer, with the window's
  options.
- **The Windows JAR is built on any machine**, Linux included, with Windows's natives and ANGLE in
  place of this machine's.
  ANGLE's DLLs come from Nucleus's build of it (`dev.nucleusframework:nucleus.angle-natives`), as
  Google ships none.

## The desktop packages

```sh
./gradlew :desktop:packageDeb    # desktop/build/packages/deb/dog-vision_0.1.0_amd64.deb
./gradlew :desktop:packageRpm    # desktop/build/packages/rpm/dog-vision-0.1.0-1.x86_64.rpm
./gradlew :cli:packageDeb        # cli/build/packages/deb/dog-vision-cli_0.1.0_all.deb
./gradlew :cli:packageRpm        # cli/build/packages/rpm/dog-vision-cli-0.1.0-1.noarch.rpm
./gradlew :desktop:packageTarGz  # desktop/build/compose/binaries/main/tar/dog-vision-0.1.0-linux-x64.tar.gz
tools/package_msi_on_linux.sh --jdk <Windows JDK 17> --jmods <Windows JDK 25's jmods> --wix <WiX 3.14>
                                 # desktop/build/compose/binaries/main/msi/dog-vision-0.1.0.msi
pwsh tools/package_msi_on_windows.ps1
                                 # on Windows: desktop/build/compose/binaries/main/msi/0.1.0/dog-vision-0.1.0.msi
```

Besides the build's own needs, making them takes these tools, on Ubuntu from the packages named:

| Package | Tools                                      | Ubuntu's packages | Run by                                           |
|---------|--------------------------------------------|-------------------|--------------------------------------------------|
| deb     | `dpkg-deb`                                 | dpkg              | `packageDeb`                                     |
| deb     | `readelf`                                  | binutils          | `debDepends`                                     |
| rpm     | `rpmbuild` 4.13 or later, `rpm`, `elfdeps` | rpm               | `rpmLibraryRequires`, `packageRpm`               |
| MSI     | Wine, a Windows JDK 17, jmods, WiX 3.14    | wine, winetricks  | `tools/package_msi_on_linux.sh`, which says more |

- **Gradle downloads Temurin 25 on the first build** that needs it, where it finds none installed,
  so that build needs the network.
- **The tar.gz needs nothing more**, and neither do the JARs.
- **The version, 0.1.0 in the names above, is `appVersion` in
  [`gradle.properties`](../gradle.properties)**, the Android app's `versionName` too.
  `-PappVersion=<version>` builds the packages with another, and the MSI scripts take
  `--app-version` and `-AppVersion` for the same.

### What each package holds

- **The deb and the rpm run on the system's Java**, 17 or newer, which they depend on: the
  distribution updates it, and a machine with a JRE downloads little more.
  Each comes in two packages:
  - `dog-vision`, the window, which converts a photo given alone as the command line does;
  - `dog-vision-cli`, the command line alone, which needs no display and so only a headless Java,
    and which `dog-vision` recommends.
- **Their files are where Debian's and Fedora's Java applications have them**:
  - the JARs in `/usr/share/dog-vision/lib` and `/usr/share/dog-vision-cli/lib`, each package with
    its own copy of the command line's, so that they share no classpath;
  - the window's native libraries, skiko's and LWJGL's, in `/usr/lib/dog-vision`, where the FHS
    puts what depends on the architecture, and which skiko and LWJGL load them from rather than
    unpack their own copies at run time;
  - the launchers, `dog-vision` and `dog-vision-cli`, in `/usr/bin`, on `PATH`;
  - the window's menu entry, `/usr/share/applications/cz.loplex.dogvision.desktop`, named in the
    system's language ("Dog vision", "Psí vidění"), and its icons in the hicolor theme.
- **Each launcher finds its Java** in `JAVA_HOME`, then on `PATH`, then the newest in
  `/usr/lib/jvm` and `/usr/lib64/jvm`, and says so where none is 17 or newer:
  the alternatives may point `java` at an older one on a machine that has a newer one as well.
  It is [`launcher.sh`](../build-logic/src/main/resources/cz/loplex/dogvision/packaging/launcher.sh),
  filled in by the build.
- **The tar.gz and the MSI bring a runtime of their own**, Temurin's JDK 25, which jlink cuts down
  to the modules the window uses, and both launchers.
  Temurin brings its own image and font libraries, which a distribution's OpenJDK takes from the
  system, so the tar.gz needs little more than glibc 2.28 or later, which LWJGL's natives ask for,
  X11, ALSA, fontconfig and the C++ runtime.
  It is to run as `dog-vision/bin/dog-vision` without installing it.
- **The MSI installs into `Program Files\dog-vision`** and adds the window to the Start menu, in a
  dog-vision group, and to the desktop.
  It carries a fixed upgrade code, so that a later version's MSI replaces it.

### The Linux packages' dependencies

- **A Java that can open a window**, or a headless one for `dog-vision-cli`:
  - the deb, `default-jre (>= 2:1.17) | java17-runtime`: the distribution's default JRE where it is
    17 or newer, as the Debian Java Policy has it, and any other JRE that provides
    `java17-runtime`, Temurin's among them, where it is not (Ubuntu 20.04's and 22.04's are 11);
  - the rpm, `(jre-17 or jre-21 or jre-25)`, a rich dependency, as no one name is provided by every
    rpm JRE of 17 or newer and by no older one: Fedora's and Rocky's Java 8 provide `jre = 1:1.8.0`,
    whose epoch puts it above `jre >= 17`.
    A new long-term release joins the list when a distribution makes it its default.
- **The libraries the window's natives link against**, and libEGL and ffmpeg, which LWJGL opens only
  once it runs and the window runs apart for a video or the camera:
  - the deb names packages as Ubuntu 20.04 names them, which later releases keep or provide, so
    that the deb is the same wherever it is built; the table `debianPackages` in
    [`DebDepends.kt`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/DebDepends.kt)
    names them, and the build fails on a library it lacks;
  - the rpm names libraries and a file (`libX11.so.6()(64bit)`, `libEGL.so.1()(64bit)`,
    `/usr/bin/ffmpeg` and the like), as Fedora and openSUSE name their packages differently.
- **Nothing else, and no scripts**: the menu entry and the icons are files of the package, which
  the desktop finds by itself.

The tasks that make them, [`DebPackage`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/DebPackage.kt)
and [`RpmPackage`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/RpmPackage.kt), call
`dpkg-deb` and `rpmbuild` themselves, in [`build-logic`](../build-logic), as jpackage always puts a
runtime into its packages.

### Trying the Linux packages

- **[`tools/test_deb.sh`](../tools/test_deb.sh) installs a deb in a bare container**, `dog-vision`
  or `dog-vision-cli`, with the Java apt chooses for it, and checks that each runs from `PATH`,
  converts [`test_photo.jpg`](../tools/test_photo.jpg) and is removed with nothing left behind.
  - Of `dog-vision`, it also opens the window under Xvfb, installed only after the deb's own
    dependencies so that its X libraries hide none the deb misses, and checks that skiko and
    LWJGL loaded the deb's natives rather than unpack their own into the home or `/tmp`.
  - It holds on Ubuntu 20.04, 22.04 and 24.04 and on Debian 12.
    Debian 11 is not among them: its support ended in August 2026, and its repositories moved to
    archive.debian.org.
- **[`tools/test_rpm.sh`](../tools/test_rpm.sh) does for an rpm what `test_deb.sh` does**, with
  dnf, zypper or microdnf; it holds on Fedora 42, openSUSE Leap 15.6 and Rocky Linux 9's minimal
  image, whose repositories lack ffmpeg.
- **With `--upgrade <a later package>`, each installs that one over the first** before removing
  it, as an update does, and checks that the later version is the one installed and does what the
  first did.
  The later package is the same build with `-PappVersion=0.1.1`, which the build writes beside the
  first, as the Linux job of [`ci.yml`](../.github/workflows/ci.yml) does.
  From a deb or an rpm of jpackage's, in `/opt/dog-vision` up to 0.1.x, the upgrade leaves nothing
  there, as tried on Ubuntu 20.04 and Fedora 42.
- **With `--temurin`, each installs Temurin's JRE from Adoptium's repository first**, and checks
  that the package takes it and no OpenJDK beside it, as it does on Ubuntu 24.04 and Fedora 42.

### The MSI, built under Wine or on Windows

jpackage builds an installer only on the system it is for, so the MSI is built either under Wine on
Linux or on Windows.

- **On Windows, [`tools/package_msi_on_windows.ps1`](../tools/package_msi_on_windows.ps1) builds
  it** with the JDK 25 it runs on and WiX 3.14, and light.exe validates it.
- **Under Wine, [`tools/package_msi_on_linux.sh`](../tools/package_msi_on_linux.sh) builds it**,
  with three detours round Wine that the script describes.
  Its runtime is linked from the jmods it is given.
  Wine cannot validate it, so the workflow
  [`msi-under-wine.yml`](../.github/workflows/msi-under-wine.yml), started by hand, builds it on
  Linux and validates, installs, upgrades and removes it on Windows.
- **[`tools/test_msi_on_windows.ps1`](../tools/test_msi_on_windows.ps1) tries it on a Windows
  machine to throw away**: it installs the MSI, runs the command line, looks at the shortcuts,
  installs a later version over it and removes that.
- **The MSI's code page is Windows-1250**, from
  [`desktop/packaging/windows`](../desktop/packaging/windows/MsiInstallerCodepage_en.wxl), as the
  vendor's name has a ř that jpackage's Windows-1252 lacks.

#### Caveat: the MSI built under Wine gives `dog-vision-cli` shortcuts too

It adds a Start menu entry and a desktop shortcut for `dog-vision-cli` as well, which started from
there only prints its usage: JDK 17's jpackage cannot leave one launcher out, where JDK 25's, on
Windows, does.
