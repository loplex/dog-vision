#!/usr/bin/env bash
# Builds the app images for Windows on x86-64 on Linux, through Wine:
# each a folder with its launchers, their JARs and a runtime of its own,
# which runs under Wine as it is, without being installed.
# Without --image both, one after the other;
# with --image dog-vision-compose the one the MSI installs, with the Compose window's dog-vision-compose.exe,
# the Swing window's dog-vision-swing.exe and the command line's dog-vision-cli.exe, which runs in a console;
# with --image dog-vision-cli the command line's, with dog-vision-cli.exe alone.
#
# Each is written to tools/build/app-image, as tools/build/app-image/dog-vision-compose, and its folder printed;
# tools/build holds what the scripts in tools build, which git ignores.
# tools/package_msi_on_linux.sh makes the MSI of the one,
# and tools/package_cli_zip_on_linux.sh the command line's zip of the other.
#
# jpackage makes an app image for Windows only on Windows,
# so this runs a Windows JDK 17's jpackage.exe under Wine, in the locale cs_CZ.UTF-8,
# with the arguments that :packaging's windowsJpackage task writes, or its windowsCliJpackage,
# which tools/package_msi_on_windows.ps1 and tools/package_cli_zip_on_windows.ps1 hand jpackage on Windows as well:
# what the package is, and what the image runs,
# its launchers' JARs and the runtime that the image's windowsRuntime or windowsCliRuntime task links.
# jpackage puts every JAR on each launcher's classpath, so this then copies the launchers' .cfg
# that the image's windowsLauncherConfigs or windowsCliLauncherConfigs task writes, each with its own classpath.
# An app image needs neither WiX nor .NET.
#
# The JDK is a 17, as Wine 11.18's TransmitFile,
# handed a file where Windows expects a socket, fails with another error than Windows's WSAENOTSOCK,
# which the JDK from 18 on takes for a failed copy ("transfer failed"), and JDK 17's jpackage copies files without it.
# The runtime is no JDK 17's: windowsRuntime or windowsCliRuntime links it
# from Temurin's jmods for Windows of the release in gradle/libs.versions.toml.
#
# Needs:
# - Wine.
#   Its prefix is WINEPREFIX's, or without it mono_msi_builder among winetricks' named prefixes where that is there,
#   which tools/make_wine_prefix_on_linux.sh makes for the MSIs, and else Wine's own.
# - The locale cs_CZ.UTF-8.
# - --jdk: a Windows JDK 17, unpacked, for its bin/jpackage.exe;
#   without it, the one that tools/fetch_msi_tools_on_linux.sh downloads into tools/cache,
#   or into the directory --tools names.
#
# --app-version gives the launchers another version than gradle.properties' appVersion,
# as an MSI's test of an upgrade needs a later one; the application in it stays the same.
#
# Run it from anywhere.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Says why on the standard error and exits, with 1 or the status given.
die() {
    echo "$1" >&2
    exit "${2:-1}"
}

usage() {
    die "usage: $0 [--tools <directory>] [--jdk <Windows JDK 17>] [--app-version <version>]
       [--image dog-vision-compose|dog-vision-cli]" 2
}

# The path as Windows programs under Wine see it: through the drive Wine maps to the root, Z:.
windows_path() {
    winepath -w "$1"
}

# Notes a command this runs that the system lacks,
# with the Debian or Ubuntu package and the Fedora package that have it.
missing=()
require() {
    local command="$1" apt="$2" dnf="$3"
    command -v "$command" >/dev/null || missing+=("$command: apt install $apt, or dnf install $dnf")
}


# The arguments.

tools="$root/tools/cache"
jdk="" app_version="" name=""
while (( $# > 0 )); do
    case "$1" in
              --tools) tools="${2:-}";       shift 2 || usage ;;
                --jdk) jdk="${2:-}";         shift 2 || usage ;;
        --app-version) app_version="${2:-}"; shift 2 || usage ;;
              --image) name="${2:-}";        shift 2 || usage ;;
        *) usage ;;
    esac
done

# Without --image, this again for each, with the same options.
if [[ -z "$name" ]]; then
    options=(--tools "$tools")
    [[ -z "$jdk" ]] || options+=(--jdk "$jdk")
    [[ -z "$app_version" ]] || options+=(--app-version "$app_version")
    for each in dog-vision-compose dog-vision-cli; do
        "${BASH_SOURCE[0]}" "${options[@]}" --image "$each"
    done
    exit 0
fi

jdk="${jdk:-$tools/jdk17}"
msi_prefix="${WINE_PREFIXES:-${XDG_DATA_HOME:-$HOME/.local/share}/wineprefixes}/mono_msi_builder"
if [[ -z "${WINEPREFIX:-}" && -f "$msi_prefix/drive_c/windows/system32/kernel32.dll" ]]; then
    export WINEPREFIX="$msi_prefix"
fi

# The image's tasks of :packaging, windows<image>Jpackage, and their folder in packaging/build.
case "$name" in
    dog-vision-compose) image_tasks="windows" image_folder="windows" ;;
        dog-vision-cli) image_tasks="windowsCli" image_folder="windows/cli" ;;
                     *) usage ;;
esac

require "wine" "wine" "wine"
require "winepath" "wine" "wine"
if (( ${#missing[@]} > 0 )); then
    die "$(printf '%s\n' "Missing commands:" "${missing[@]/#/  }")"
fi
locale -a | grep -ix 'cs_CZ\.utf-\?8' >/dev/null ||
    die "Missing locale cs_CZ.UTF-8: locale-gen cs_CZ.UTF-8, or dnf install glibc-langpack-cs"

[[ -f "$jdk/bin/jpackage.exe" ]] ||
    die "$jdk/bin/jpackage.exe does not exist: tools/fetch_msi_tools_on_linux.sh downloads it" 2
jdk="$(realpath "$jdk")"
jpackage="$jdk/bin/jpackage.exe"
# windowsJpackage writes the paths as Wine sees them through Z:, which Wine maps to the root.
[[ "$(winepath -u "Z:\\" 2>/dev/null)" == "/" ]] || die "Wine maps no drive Z: to /, which the paths need"


# What jpackage takes in: the arguments, with the JARs and the runtime they name.

gradle_options=()
[[ -z "$app_version" ]] || gradle_options+=("-PwindowsAppVersion=$app_version")
"$root/gradlew" --quiet ":packaging:${image_tasks}Jpackage" "${gradle_options[@]}"
arguments="$root/packaging/build/$image_folder/jpackage"
# jpackage refuses an image's folder that is there already.
destination="$root/tools/build/app-image"
rm -rf "${destination:?}/$name"
mkdir -p "$destination"


# jpackage, whose words go to the standard error, so that the image's folder is all this prints.
# It reads the files of arguments as UTF-8, as windowsJpackage writes them, only when told to:
# JDK 17's default charset is the system's.

export WINEDEBUG="${WINEDEBUG:--all}"
export LC_ALL="cs_CZ.UTF-8"
wine "$jpackage" \
    -J-Dfile.encoding=UTF-8 \
    "@$(windows_path "$arguments/arguments")" \
    --type "app-image" \
    --dest "$(windows_path "$destination")" >&2
image="$destination/$name"


# Each launcher with its own classpath, in its order;
# the licence, and what the image holds that is not this project's own, in its folder.

"$root/gradlew" --quiet ":packaging:${image_tasks}LauncherConfigs" "-PwindowsAppImage=$image" \
    "${gradle_options[@]}"
cp "$root/packaging/build/$image_folder/launchers/"*.cfg "$image/app/"
cp "$root/packaging/build/$image_folder/launchers/"{LICENSE,THIRD-PARTY-LICENSES.txt} "$image/"

echo "$image"
