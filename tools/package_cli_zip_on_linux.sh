#!/usr/bin/env bash
# Builds the command line's zip for Windows on x86-64 on Linux, through Wine:
# the same zip as tools/package_cli_zip_on_windows.ps1 builds on Windows,
# which says what it holds, written to tools/build/zip, which git ignores.
#
# It zips the command line's app image, dog-vision-cli,
# which tools/package_app_image_on_linux.sh builds under Wine, with the licence beside it.
#
# Needs:
# - What package_app_image_on_linux.sh needs, which --tools and --jdk are handed to.
# - zip.
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
    die "usage: $0 [--tools <directory>] [--jdk <Windows JDK 17>]" 2
}


# The arguments.

image_options=(--image dog-vision-cli)
while (( $# > 0 )); do
    case "$1" in
        --tools|--jdk) [[ -n "${2:-}" ]] || usage; image_options+=("$1" "$2"); shift 2 ;;
        *) usage ;;
    esac
done

command -v zip >/dev/null || die "Missing commands:
  zip: apt install zip, or dnf install zip"


# The image, which has the licences in its folder, a copy of it, and its zip.

# The pattern stays unquoted after =~, where quotes would make it a plain string.
version_pattern=$'(^|\n)appVersion=([^\n]+)'
[[ "$(<"$root/gradle.properties")" =~ $version_pattern ]] || die "gradle.properties has no appVersion"
version="${BASH_REMATCH[2]}"
image="$("$root/tools/package_app_image_on_linux.sh" "${image_options[@]}")"

# A copy, which the zip is made of.
staging="$root/tools/build/staging/cli-zip"
rm -rf "${staging:?}"
mkdir -p "$staging"
cp -a "$image" "$staging/"
output="$root/tools/build/zip"
mkdir -p "$output"
zip="$output/dog-vision-cli-$version-windows-x64.zip"
rm -f "$zip"
# The image's folder at the zip's root, so that it unpacks into a folder of its own.
(cd "$staging" && zip -qr "$zip" "dog-vision-cli")

echo "$zip"
