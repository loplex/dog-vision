#!/usr/bin/env bash
# Runs the Swing window as built, gui-swing/build/jars/dog-vision-swing-linux-x64-<version>.jar,
# which `./gradlew :gui-swing:linuxUberJar` builds,
# with the window's arguments, on $JAVA_HOME's java or the one on PATH, a Java 17 or newer that is not headless.
#
# Run it from anywhere; a relative path among the arguments is taken from the current directory:
#   tools/run_swing_on_linux.sh              # the camera
#   tools/run_swing_on_linux.sh photo.jpg    # a photo, or a video played over and over
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The pattern stays unquoted after =~, where quotes would make it a plain string.
version_pattern=$'(^|\n)appVersion=([^\n]+)'
[[ "$(<"$root/gradle.properties")" =~ $version_pattern ]] || { echo "gradle.properties has no appVersion" >&2; exit 1; }
jar="$root/gui-swing/build/jars/dog-vision-swing-linux-x64-${BASH_REMATCH[2]}.jar"
[[ -f "$jar" ]] || { echo "No $jar: ./gradlew :gui-swing:linuxUberJar builds it" >&2; exit 1; }

exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$jar" "$@"
