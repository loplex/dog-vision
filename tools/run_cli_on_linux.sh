#!/usr/bin/env bash
# Runs the command line as built, cli/build/jars/dog-vision-cli.jar, which `./gradlew :cli:uberJar` builds,
# with the arguments given, on $JAVA_HOME's java or the one on PATH, a Java 17 or newer.
#
# Run it from anywhere; a relative path among the arguments is taken from the current directory:
#   tools/run_cli_on_linux.sh --help
#   tools/run_cli_on_linux.sh photo.jpg
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

jar="$root/cli/build/jars/dog-vision-cli.jar"
[[ -f "$jar" ]] || { echo "No $jar: ./gradlew :cli:uberJar builds it" >&2; exit 1; }

exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$jar" "$@"
