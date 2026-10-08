#!/usr/bin/env bash
# Takes the screenshots in docs/images again, each in English with the apple photo beside them open:
#   window.png, window-swing.png  the Compose and the Swing window, under Xvfb, at 1400 x 820;
#   web.png                       the page :web:jsBrowserDistribution builds, in headless Chrome, at 1400 x 820;
#   android.png                   the app on the device adb sees, by DocsScreenshotTest, cut to what the app draws
#                                 between the system's bars and scaled to 580 wide. The documents' comes from an
#                                 emulator with Android 17, a screen of 1080 x 2400 at 420 dpi.
# The windows' fonts and widgets are the machine's; the page's and the app's are Chrome's and Android's own.
#
# Usage: tools/take_screenshots.sh [windows] [web] [app]   all three when none is named
# Needs Xvfb, openbox, xdotool and ImageMagick for the windows,
# google-chrome for the page, adb with one device for the app.
# Run it from anywhere.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
images="$root/docs/images"
photo="$images/shiny-red-apples.jpg"
display=":${SCREENSHOT_DISPLAY:-99}"
width=1400
height=820
app_width=580
chrome="${CHROME:-google-chrome}"

tmp="$(mktemp -d)"
pids=()
cleanup() {
    for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
    rm -rf "$tmp"
}
trap cleanup EXIT

gradle() { "$root/gradlew" --project-dir "$root" --quiet "$@"; }

geometry() {
    DISPLAY="$display" xdotool getwindowgeometry --shell "$1" |
        awk -F= '/^WIDTH=/ { w = $2 } /^HEIGHT=/ { h = $2 } END { print w "x" h }'
}

# One window, run by its Gradle task, found by its English title, resized and captured, then asked to quit.
window() {
    local task="$1" out="$2" id jvm
    env -u WAYLAND_DISPLAY DISPLAY="$display" XDG_CONFIG_HOME="$tmp/config" LANGUAGE=en \
        "$root/gradlew" --project-dir "$root" --quiet "$task" --args="$photo" &>"$tmp/$task.log" &
    local gradle_pid=$!
    pids+=("$gradle_pid")
    id="$(DISPLAY="$display" timeout 300 xdotool search --sync --name '^Dog Vision$' | head -1)"
    [ -n "$id" ] || { echo "no window from $task; its log:" >&2; cat "$tmp/$task.log" >&2; exit 1; }
    # The Compose window sets its own size once it is shown, which undoes a resize that came first.
    sleep 2
    for _ in 1 2 3 4 5; do
        DISPLAY="$display" xdotool windowsize --sync "$id" "$width" "$height"
        sleep 2
        [ "$(geometry "$id")" = "${width}x${height}" ] && break
    done
    [ "$(geometry "$id")" = "${width}x${height}" ] ||
        { echo "the window of $task stays $(geometry "$id"), not ${width}x${height}" >&2; exit 1; }
    DISPLAY="$display" import -window "$id" "$tmp/window.png"
    convert "$tmp/window.png" -strip "$out"
    [ "$(identify -format '%wx%h' "$out")" = "${width}x${height}" ] ||
        { echo "$out is $(identify -format '%wx%h' "$out"), not ${width}x${height}" >&2; exit 1; }
    jvm="$(DISPLAY="$display" xdotool getwindowpid "$id")"
    DISPLAY="$display" xdotool windowactivate --sync "$id" key ctrl+q
    timeout 30 tail --pid="$jvm" -f /dev/null || kill "$jvm"
    wait "$gradle_pid" || true
    echo "$out"
}

windows() {
    Xvfb "$display" -screen 0 1600x1000x24 &>"$tmp/xvfb.log" &
    pids+=($!)
    sleep 1
    env -u WAYLAND_DISPLAY DISPLAY="$display" openbox &>"$tmp/openbox.log" &
    pids+=($!)
    sleep 1
    window :gui-compose:run "$images/window.png"
    window :gui-swing:runJvm "$images/window-swing.png"
}

web() {
    gradle :web:jsBrowserDistribution
    python3 -I "$root/tools/web_screenshot.py" "$chrome" \
        "$root/web/build/dist/js/productionExecutable/index.html" "$photo" "$tmp/web.png"
    convert "$tmp/web.png" -strip "$images/web.png"
    echo "$images/web.png"
}

app() {
    local outputs="$root/android/build/outputs/connected_android_test_additional_output" shot
    touch "$tmp/before"
    gradle :android:connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class=cz.loplex.dogvision.DocsScreenshotTest
    shot="$(find "$outputs" -name android.png -newer "$tmp/before" | head -1)"
    [ -n "$shot" ] || { echo "DocsScreenshotTest wrote no android.png under $outputs" >&2; exit 1; }
    convert "$shot" -resize "${app_width}x" -strip "$images/android.png"
    echo "$images/android.png"
}

parts=("$@")
[ ${#parts[@]} -gt 0 ] || parts=(windows web app)
for part in "${parts[@]}"; do
    case "$part" in
        windows | web | app) "$part" ;;
        *) echo "unknown part $part; windows, web or app" >&2; exit 2 ;;
    esac
done
