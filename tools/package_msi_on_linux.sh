#!/usr/bin/env bash
# Builds the desktop window's MSI for Windows on x86-64 on Linux, through Wine.
#
# jpackage makes an installer only on the system the installer is for, so this runs a Windows JDK's
# jpackage.exe under Wine, over the JAR that `:desktop:windowsUberJar` assembles, with WiX Toolset 3
# making the MSI. As the Linux packages have, it has a runtime of its own, the window's launcher
# dog-vision.exe, and dog-vision-cli.exe, the command line alone, which runs in a console. It is
# written to desktop/build/compose/binaries/main/msi.
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
# Run it from anywhere.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
desktop="$root/desktop"
packaging="$desktop/packaging"
output="$desktop/build/compose/binaries/main/msi"

# Every later version's MSI replaces the one installed, as Windows Installer tells versions of one
# product apart by it: never change it.
upgrade_uuid="602aa86b-3230-4786-8460-ba08bca42e45"

# The runtime's modules, the Linux packages' too: those Compose always takes, and the ones
# desktop/build.gradle.kts adds.
modules="java.base,java.desktop,java.logging,jdk.crypto.ec,java.instrument,jdk.unsupported"

# Says why on the standard error and exits, with 1 or the status given.
die() {
    echo "$1" >&2
    exit "${2:-1}"
}

usage() {
    die "usage: $0 --jdk <Windows JDK 17> --jmods <the Windows JDK's jmods> --wix <WiX 3.14's binaries>" 2
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

jdk="" jmods="" wix=""
while (( $# > 0 )); do
    case "$1" in
          --jdk) jdk="${2:-}";   shift 2 || usage ;;
        --jmods) jmods="${2:-}"; shift 2 || usage ;;
          --wix) wix="${2:-}";   shift 2 || usage ;;
        *) usage ;;
    esac
done
[[ -n "$jdk" && -n "$jmods" && -n "$wix" ]] || usage

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

# The packages' version, as desktop/build.gradle.kts gives it to the Linux ones. The pattern stays
# unquoted after =~, where quotes would make it a plain string.
version_pattern='packageVersion = "([^"]+)"'
[[ "$(<"$desktop/build.gradle.kts")" =~ $version_pattern ]] || die "desktop/build.gradle.kts has no packageVersion"
version="${BASH_REMATCH[1]}"
"$root/gradlew" --quiet ":desktop:windowsUberJar"
jar="$desktop/build/compose/jars/dog-vision-windows-x64-$version.jar"

staging="$desktop/build/windows-msi"
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
msi="$output/dog-vision-$version.msi"
rm -f "$msi"


# jpackage.

# jpackage finds WiX on the PATH, which Wine takes from WINEPATH.
WINEPATH="$(windows_path "$wix")"
export WINEPATH
export WINEDEBUG="${WINEDEBUG:--all}"
export LC_ALL="cs_CZ.UTF-8"
temp="$staging/temp"

# The Java option is Compose's own launchers': its application then gives Swing the system's look. The
# resource directory holds MsiInstallerCodepage_en.wxl, for the code page the vendor's name needs. The
# menu group is not jpackage's "Unknown".
if log="$(wine "$jpackage" \
    --type "msi" \
    --name "dog-vision" \
    --app-version "$version" \
    --vendor "Martin Lopatář" \
    --description "How a dog or a cat sees a photo, a video or the camera" \
    --license-file "$(windows_path "$root/LICENSE")" \
    --icon "$(windows_path "$packaging/dog-vision.ico")" \
    --input "$(windows_path "$staging/input")" \
    --main-jar "$(basename "$jar")" \
    --main-class "cz.loplex.dogvision.desktop.MainKt" \
    --java-options "-Dcompose.application.configure.swing.globals=true" \
    --runtime-image "$(windows_path "$staging/runtime")" \
    --add-launcher "dog-vision-cli=$(windows_path "$packaging/dog-vision-cli.properties")" \
    --resource-dir "$(windows_path "$packaging/windows")" \
    --win-menu \
    --win-menu-group "dog-vision" \
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
    cd "$temp/images/win-msi.image/dog-vision"
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
