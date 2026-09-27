#!/usr/bin/env bash
# Installs a deb of the project's, dog-vision or dog-vision-cli, in a bare container, runs it and
# removes it, checking each step. With --upgrade, it installs a later deb over the first before
# removing it, as an update does. With --temurin, it installs Adoptium's Temurin JRE first, from
# Adoptium's repository, and checks that the deb takes it rather than an OpenJDK.
#
# A bare image has no desktop and none of its folders, /usr/share/applications among them, which
# is what a headless install for the command line alone meets. apt installs the deb there with its
# dependencies, a Java among them. dog-vision's window is then opened under Xvfb, which is
# installed only once the deb's own dependencies are, so that its X libraries hide none the deb
# misses. Each check says whether it held, and every check runs, so that one failing does not hide
# the others; the script fails if any did.
#
# Both convert test_photo.jpg, beside this script, which ffmpeg made:
#   ffmpeg -f lavfi -i testsrc2=size=160x120:rate=1 -frames:v 1 -q:v 4 test_photo.jpg
#
# A later deb is the same build with another version, `-PappVersion=0.1.1`, which
# `:desktop:packageDeb` and `:cli:packageDeb` write beside the first.
#
# Needs docker, or podman installed as docker. Usage:
#   tools/test_deb.sh [--temurin] [--upgrade <a later deb>] <the deb> [image, ubuntu:20.04 by
#   default]
set -euo pipefail

# Says why on the standard error and exits, with 1 or the status given.
die() {
    echo "$1" >&2
    exit "${2:-1}"
}

usage() {
    die "usage: $0 [--temurin] [--upgrade <a later deb>] <the deb> [image]" 2
}

mounts=()
jre=distribution
if [[ "${1:-}" == --temurin ]]; then
    jre=temurin
    shift
fi
if [[ "${1:-}" == --upgrade ]]; then
    (( $# >= 2 )) || usage
    later="$(realpath "$2")"
    [[ -f "$later" ]] || die "$later does not exist" 2
    mounts+=(-v "$later:/deb/later/package.deb:ro")
    shift 2
fi
(( $# >= 1 && $# <= 2 )) || usage
deb="$(realpath "$1")"
image="${2:-ubuntu:20.04}"
[[ -f "$deb" ]] || die "$deb does not exist" 2
photo="$(dirname "$(realpath "$0")")/test_photo.jpg"
[[ -f "$photo" ]] || die "$photo does not exist"
command -v docker >/dev/null || die "Missing command: docker"

# What runs in the container, as root, with the deb at /deb/package.deb, the later one, if any, at
# /deb/later/package.deb, and the photo at /photo/test_photo.jpg.
docker run --rm -i -e "JRE=$jre" -v "$deb:/deb/package.deb:ro" -v "$photo:/photo/test_photo.jpg:ro" \
    "${mounts[@]}" "$image" sh -s <<'EOF'
package="$(dpkg-deb -f /deb/package.deb Package)"
# What a check shows although check hides its output: fd 3.
exec 3>&1
failed=0
check() {
    what="$1"
    shift
    if "$@" >/dev/null 2>&1; then
        echo "ok: $what"
    else
        echo "FAILED: $what"
        failed=$((failed + 1))
    fi
}
# dpkg's state of the package: installed, half-configured, config-files, not-installed and the like.
state() {
    dpkg-query -W -f='${db:Status-Status}' "$package" 2>/dev/null || echo not-installed
}
is_installed() {
    [ "$(state)" = installed ]
}
is_removed() {
    [ "$(state)" = not-installed ] || [ "$(state)" = config-files ]
}
is_version() {
    [ "$(dpkg-query -W -f='${Version}' "$package")" = "$1" ]
}
# The command $1 converts the photo to a PNG beside it, as the command line does with a photo alone.
converts() {
    rm -rf /tmp/photo && mkdir /tmp/photo && cp /photo/test_photo.jpg /tmp/photo/ &&
        "$1" /tmp/photo/test_photo.jpg &&
        [ "$(od -An -tx1 -N8 /tmp/photo/test_photo.dog.png | tr -d ' \n')" = 89504e470d0a1a0a ]
}
# dog-vision shows the photo in a window called Dog vision on the display :99, which Xvfb draws in
# memory; the window's output is in /tmp/window.log, and its home is /tmp/home.
window_opens() {
    [ -e /tmp/.X11-unix/X99 ] || { Xvfb :99 -screen 0 1280x800x24 >/tmp/xvfb.log 2>&1 & }
    for _ in 1 2 3 4 5 6 7 8 9 10; do [ -e /tmp/.X11-unix/X99 ] && break; sleep 1; done
    rm -rf /tmp/home && mkdir /tmp/home
    DISPLAY=:99 HOME=/tmp/home dog-vision --window /photo/test_photo.jpg >/tmp/window.log 2>&1 &
    window=$!
    shown=""
    for _ in $(seq 60); do
        if DISPLAY=:99 xwininfo -root -tree 2>/dev/null | grep -q '"Dog vision"'; then
            shown=yes
            break
        fi
        kill -0 "$window" 2>/dev/null || break
        sleep 1
    done
    kill "$window" 2>/dev/null
    wait "$window" 2>/dev/null
    [ -n "$shown" ] || tail -n 20 /tmp/window.log >&3
    [ -n "$shown" ]
}
# skiko and LWJGL loaded the libraries the deb installs rather than unpack their own copies, into
# the home or /tmp, as they do where no library path names them.
unpacks_no_natives() {
    ! ls -d /tmp/home/.skiko /tmp/home/.lwjgl* /tmp/lwjgl* 2>/dev/null | grep -q .
}
# Xvfb and xwininfo, for the window.
install_display() {
    apt-get install -y -qq xvfb x11-utils >/tmp/display.log 2>&1 || tail -n 20 /tmp/display.log
}
# Temurin's JRE from Adoptium's repository, as its instructions have it.
install_temurin() {
    apt-get install -y -qq wget gpg ca-certificates >/tmp/temurin.log 2>&1 &&
        wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public |
        gpg --dearmor >/etc/apt/trusted.gpg.d/adoptium.gpg &&
        echo "deb https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" \
            >/etc/apt/sources.list.d/adoptium.list &&
        apt-get update -qq >>/tmp/temurin.log 2>&1 &&
        apt-get install -y -qq temurin-25-jre >>/tmp/temurin.log 2>&1
}
no_openjdk() {
    ! dpkg-query -W -f='${db:Status-Status} ${Package}\n' 2>/dev/null | grep -q '^installed openjdk'
}
# What the package does once installed; $1 is added to each check's name.
check_installed() {
    command -v java >/dev/null && echo "note: java is $(readlink -f "$(command -v java)")"
    [ "$JRE" = temurin ] && check "no OpenJDK is installed beside Temurin$1" no_openjdk
    case "$package" in
        dog-vision)
            check "the window is in the desktop menu folder$1" \
                test -f /usr/share/applications/cz.loplex.dogvision.desktop
            # jpackage's deb up to 0.1.x, with its runtime, which an upgrade replaces.
            if [ -x /opt/dog-vision/bin/dog-vision ]; then
                echo "note: this dog-vision is jpackage's, in /opt/dog-vision"
                check "the command line runs$1" /opt/dog-vision/bin/dog-vision-cli --help
                return
            fi
            check "dog-vision runs from PATH$1" dog-vision --help
            check "it converts a JPEG to a PNG$1" converts dog-vision
            check "nothing is left in /opt/dog-vision$1" test ! -e /opt/dog-vision
            command -v Xvfb >/dev/null || install_display
            check "its window opens under Xvfb$1" window_opens
            check "skiko and LWJGL load the deb's natives$1" unpacks_no_natives
            ;;
        dog-vision-cli)
            check "dog-vision-cli runs from PATH$1" dog-vision-cli --help
            check "it converts a JPEG to a PNG$1" converts dog-vision-cli
            ;;
        *)
            echo "FAILED: no checks for the package $package"
            failed=$((failed + 1))
            ;;
    esac
}
# What the package leaves behind once removed: nothing of its own.
check_removed() {
    case "$package" in
        dog-vision)
            check "its menu entry is gone" test ! -e /usr/share/applications/cz.loplex.dogvision.desktop
            check "its icons are gone" test ! -e /usr/share/icons/hicolor/256x256/apps/cz.loplex.dogvision.png
            check "/usr/bin/dog-vision is gone" test ! -e /usr/bin/dog-vision
            check "/usr/share/dog-vision is gone" test ! -e /usr/share/dog-vision
            check "/usr/lib/dog-vision is gone" test ! -e /usr/lib/dog-vision
            check "/opt/dog-vision is gone" test ! -e /opt/dog-vision
            ;;
        dog-vision-cli)
            check "/usr/bin/dog-vision-cli is gone" test ! -e /usr/bin/dog-vision-cli
            check "/usr/share/dog-vision-cli is gone" test ! -e /usr/share/dog-vision-cli
            ;;
    esac
}

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
[ -e /usr/share/applications ] && echo "note: the image has /usr/share/applications already"

if [ "$JRE" = temurin ]; then
    install_temurin || tail -n 20 /tmp/temurin.log
    check "Temurin's JRE installs from Adoptium's repository" dpkg-query -W temurin-25-jre
fi
apt-get install -y -qq /deb/package.deb >/tmp/install.log 2>&1 || tail -n 20 /tmp/install.log
check "$package installs and is configured" is_installed
check_installed ""

# The old package's prerm and postrm run with upgrade, the new one's preinst with upgrade and its
# postinst with configure and the old version; the menu entry passes from the one to the other.
if [ -e /deb/later/package.deb ]; then
    version="$(dpkg-deb -f /deb/later/package.deb Version)"
    apt-get install -y -qq /deb/later/package.deb >/tmp/upgrade.log 2>&1 || tail -n 20 /tmp/upgrade.log
    check "$package $version installs over it and is configured" is_installed
    check "the installed version is $version" is_version "$version"
    check_installed " after the upgrade"
fi

apt-get remove -y -qq "$package" >/tmp/remove.log 2>&1 || tail -n 20 /tmp/remove.log
check "$package is removed" is_removed
check_removed

if [ "$failed" -eq 0 ]; then
    echo "Every check held."
else
    echo "$failed check(s) failed."
    exit 1
fi
EOF
