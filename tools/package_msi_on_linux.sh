#!/usr/bin/env bash
# Builds a desktop window's MSI for Windows on x86-64 on Linux, through Wine: with --window compose,
# the default, dog-vision's, the Compose window's; with --window swing, dog-vision-swing's.
#
# jpackage makes an installer only on the system the installer is for, so this runs a Windows JDK's
# jpackage.exe under Wine, over the JAR that the window's windowsUberJar task assembles, with WiX
# Toolset 3 making the MSI. As the tar.gz has, it has a runtime of its own, the window's launcher,
# dog-vision.exe or dog-vision-swing.exe, and dog-vision-cli.exe, the command line alone, which
# runs in a console. It is written to desktop/build/compose/binaries/main/msi or
# swing/build/packages/msi.
#
# Three steps go round Wine 11.18, where they fail:
# - Wine's TransmitFile, handed a file where Windows expects a socket, fails with another error than
#   Windows's WSAENOTSOCK, which the JDK from 18 on takes for a failed copy ("transfer failed"). So
#   jpackage.exe comes from a JDK 17, which copies files without it, and the runtime, a JDK 25 as on
#   Linux, is linked by this machine's jlink from the Windows JDK's jmods.
# - light.exe's validation of the MSI (ICE) fails in Wine's msi.dll with 0x65B, so light.exe runs a
#   second time without it (-sval), as electron-builder runs it off Windows; jpackage has no way to
#   pass the switch. .github/workflows/msi-under-wine.yml validates the MSI on Windows instead.
# - WiX 3 finds its own version through .NET's FileVersionInfo, which Wine Mono leaves empty, and
#   jpackage then does not recognise it: the prefix needs Microsoft's .NET Framework 4.8
#   (`winetricks dotnet48`).
#
# Needs:
# - Wine, with .NET Framework 4.8 in the prefix; WINEPREFIX chooses the prefix, as it does for Wine.
# - The locale cs_CZ.UTF-8, which Wine runs in: JDK 17's jpackage reads its arguments in Windows's
#   ANSI code page, which Wine takes from the locale, and the vendor's ř is in Windows-1250, a Czech
#   locale's, not in Windows-1252, an English locale's or C's, where it becomes "?" and jpackage
#   fails on it.
# - --jdk: a Windows JDK 17, unpacked, for its bin/jpackage.exe.
# - --jmods: the Windows JDK's jmods, unpacked, of the same version as this machine's jlink (Temurin
#   ships them apart from the JDK, as OpenJDK25U-jmods_x64_windows_*.zip).
# - --wix: WiX Toolset 3.14's binaries, unpacked, the directory holding candle.exe and light.exe
#   (wix314-binaries.zip from github.com/wixtoolset/wix3). WiX 3 and 5 are free under the MS-RL;
#   from WiX 6 on, the binaries come under the Open Source Maintenance Fee's EULA, and JDK 17's
#   jpackage takes WiX 3 alone.
#
# --app-version gives the MSI another version than gradle.properties' appVersion, as a test of an
# upgrade needs a later one; the application in it stays the same.
#
# Run it from anywhere.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# The icons and dog-vision-cli's properties, which both windows' MSIs take.
packaging="$root/desktop/packaging"

# Says why on the standard error and exits, with 1 or the status given.
die() {
    echo "$1" >&2
    exit "${2:-1}"
}

usage() {
    die "usage: $0 --jdk <Windows JDK 17> --jmods <the Windows JDK's jmods> --wix <WiX 3.14's binaries>
       [--app-version <version>] [--window compose|swing]" 2
}

# The path as Windows programs under Wine see it: through the drive Wine maps to the root, Z:.
windows_path() {
    winepath -w "$1"
}

# Notes a command this runs that the system lacks, with the Debian or Ubuntu package and the Fedora
# package that have it.
missing=()
require() {
    local command="$1" apt="$2" dnf="$3"
    command -v "$command" >/dev/null || missing+=("$command: apt install $apt, or dnf install $dnf")
}


# The arguments.

jdk="" jmods="" wix="" app_version="" window=compose
while (( $# > 0 )); do
    case "$1" in
                --jdk) jdk="${2:-}";         shift 2 || usage ;;
              --jmods) jmods="${2:-}";       shift 2 || usage ;;
                --wix) wix="${2:-}";         shift 2 || usage ;;
        --app-version) app_version="${2:-}"; shift 2 || usage ;;
             --window) window="${2:-}";      shift 2 || usage ;;
        *) usage ;;
    esac
done
[[ -n "$jdk" && -n "$jmods" && -n "$wix" ]] || usage

# What each window's MSI is made of. Every later version's MSI replaces the one installed with the
# same upgrade code, as Windows Installer tells versions of one product apart by it: never change
# either, nor give both windows one, as installing the one would then remove the other. The
# runtime's modules are the tar.gz's: for Compose those it always takes and the ones
# desktop/build.gradle.kts adds, for Swing those swing/build.gradle.kts names. Compose's launchers
# pass the Java option, with which its application gives Swing the system's look.
case "$window" in
    compose)
        name="dog-vision"
        module="desktop"
        jar_task=":desktop:windowsUberJar"
        jar_dir="$root/desktop/build/compose/jars"
        output="$root/desktop/build/compose/binaries/main/msi"
        main_class="cz.loplex.dogvision.desktop.MainKt"
        description="How a dog or a cat sees a photo, a video or the camera"
        upgrade_uuid="602aa86b-3230-4786-8460-ba08bca42e45"
        modules="java.base,java.desktop,java.logging,jdk.crypto.ec,java.instrument,jdk.unsupported"
        java_options=(--java-options "-Dcompose.application.configure.swing.globals=true")
        ;;
    swing)
        name="dog-vision-swing"
        module="swing"
        jar_task=":swing:windowsUberJar"
        jar_dir="$root/swing/build/jars"
        output="$root/swing/build/packages/msi"
        main_class="cz.loplex.dogvision.swing.MainKt"
        description="How a dog or a cat sees a photo, a video or the camera, in Swing"
        upgrade_uuid="acf6164b-f4f9-4430-b4f0-939242f187fb"
        modules="java.base,java.desktop,java.instrument,jdk.unsupported"
        java_options=()
        ;;
    *) usage ;;
esac

require "wine" "wine" "wine"
require "winepath" "wine" "wine"
require "jlink" "openjdk-25-jdk-headless" "java-25-openjdk-devel"
if (( ${#missing[@]} > 0 )); then
    die "$(printf '%s\n' "Missing commands:" "${missing[@]/#/  }")"
fi
locale -a | grep -ix 'cs_CZ\.utf-\?8' >/dev/null ||
    die "Missing locale cs_CZ.UTF-8: locale-gen cs_CZ.UTF-8, or dnf install glibc-langpack-cs"

jpackage="$(realpath "$jdk")/bin/jpackage.exe"
[[ -f "$jpackage" ]] || die "$jpackage does not exist" 2
[[ -f "$jmods/java.base.jmod" ]] || die "$jmods has no java.base.jmod" 2
wix="$(realpath "$wix")"
[[ -f "$wix/light.exe" ]] || die "$wix has no light.exe" 2


# What jpackage takes in: the JAR and a runtime.

# The app's version, as gradle.properties gives it to the Linux packages and the JAR's name, unless
# --app-version gives another. The pattern stays unquoted after =~, where quotes would make it a
# plain string.
version_pattern=$'(^|\n)appVersion=([^\n]+)'
[[ "$(<"$root/gradle.properties")" =~ $version_pattern ]] || die "gradle.properties has no appVersion"
gradle_version="${BASH_REMATCH[2]}"
version="${app_version:-$gradle_version}"
"$root/gradlew" --quiet "$jar_task"
jar="$jar_dir/$name-windows-x64-$gradle_version.jar"

staging="$root/$module/build/windows-msi"
rm -rf "${staging:?}"

# jpackage takes every file in --input into the application, so the JAR goes there alone.
mkdir -p "$staging/input"
cp -p "$jar" "$staging/input/"

# The options jpackage links a runtime with itself.
jlink \
    --module-path "$(realpath "$jmods")" \
    --add-modules "$modules" \
    --strip-native-commands --strip-debug --no-man-pages --no-header-files \
    --output "$staging/runtime"

mkdir -p "$output"
msi="$output/$name-$version.msi"
rm -f "$msi"


# jpackage.

# jpackage finds WiX on the PATH, which Wine takes from WINEPATH.
WINEPATH="$(windows_path "$wix")"
export WINEPATH
export WINEDEBUG="${WINEDEBUG:--all}"
export LC_ALL="cs_CZ.UTF-8"
temp="$staging/temp"

# The resource directory holds MsiInstallerCodepage_en.wxl, for the code page the vendor's name needs.
# The menu group, the package's name, is not jpackage's "Unknown".
if log="$(wine "$jpackage" \
    --type "msi" \
    --name "$name" \
    --app-version "$version" \
    --vendor "Martin Lopatář" \
    --description "$description" \
    --license-file "$(windows_path "$root/LICENSE")" \
    --icon "$(windows_path "$packaging/dog-vision.ico")" \
    --input "$(windows_path "$staging/input")" \
    --main-jar "$(basename "$jar")" \
    --main-class "$main_class" \
    "${java_options[@]}" \
    --runtime-image "$(windows_path "$staging/runtime")" \
    --add-launcher "dog-vision-cli=$(windows_path "$packaging/dog-vision-cli.properties")" \
    --resource-dir "$(windows_path "$packaging/windows")" \
    --win-menu \
    --win-menu-group "$name" \
    --win-shortcut \
    --win-dir-chooser \
    --win-upgrade-uuid "$upgrade_uuid" \
    --temp "$(windows_path "$temp")" \
    --dest "$(windows_path "$output")" 2>&1)"; then
    status=0
else
    status=$?
fi


# light.exe a second time, where jpackage's failed.

if (( status != 0 )); then
    if [[ "$log" == *"Download WiX"* ]]; then
        die "jpackage finds no WiX: the prefix needs .NET Framework 4.8 (winetricks dotnet48)"
    fi
    if [[ "$log" != *"light.exe"* ]]; then
        printf '%s\n' "$log" >&2
        die "jpackage failed with $status before light.exe"
    fi

    # jpackage's order: each localization of the resource directory before its own.
    localizations=()
    for wxl in "$packaging"/windows/*.wxl; do
        localizations+=(-loc "$(windows_path "$wxl")")
    done

    # The objects jpackage left in its temp directory.
    objects=()
    for object in "$temp"/wixobj/*.wixobj; do
        objects+=("$(windows_path "$object")")
    done

    # As jpackage ran it, but without the MSI's validation, and where jpackage runs it: the files the
    # objects name are relative to the application's image.
    cd "$temp/images/win-msi.image/$name"
    wine "$wix/light.exe" \
        -nologo -spdb -sval \
        -ext "WixUtilExtension" \
        -ext "WixUIExtension" \
        -out "$(windows_path "$msi")" \
        -b "$(windows_path "$temp/config")" \
        -sice:ICE27 \
        "${localizations[@]}" \
        -loc "$(windows_path "$temp/config/MsiInstallerStrings_en.wxl")" \
        -cultures:en-us \
        "${objects[@]}"
fi

echo "$msi"
