#!/usr/bin/env bash
# Installs a deb of the project's, dog-vision-compose, dog-vision-swing, dog-vision-cli, dog-vision-common,
# dog-vision-web, dog-vision-web-sourcemap or dog-vision, which holds the first five,
# in a bare container, runs it and removes it, checking each step.
# With --upgrade, it installs a later deb over the first before removing it, as an update does.
# With --temurin, it installs Adoptium's Temurin JRE first,
# from Adoptium's repository, and checks that the deb takes it rather than an OpenJDK.
#
# With --switch, it then installs another deb: in the first one's place where it conflicts with it,
# as dog-vision and each package it holds do with one another, and beside it otherwise.
# It checks that apt removed every package the other deb conflicts with,
# and that of the first deb's files only those an installed package owns are left,
# then goes on with the other deb as the package, which it removes in the end,
# after the first deb where that stays installed, checking what each leaves behind.
#
# A bare image has no desktop and none of its folders, /usr/share/applications among them,
# which is what a headless install for the command line alone meets.
# apt installs the deb there with its dependencies, a Java among them.
# Each window is then opened under Xvfb, which is installed only once the deb's own dependencies are,
# so that its X libraries hide none the deb misses.
# No browser runs there, so of dog-vision-web it checks the desktop entry, with desktop-file-validate,
# and that the file its launcher opens with xdg-open is there.
# Each check says whether it held, and every check runs, so that one failing does not hide the others;
# the script fails if any did.
#
# A deb of the project's that the deb depends on at its own version,
# as dog-vision-web-sourcemap does on dog-vision-web and the command line and both windows on dog-vision-common,
# which no repository has, is installed with it from the deb's own folder,
# and so is the later one's from the later deb's.
#
# The command line converts test_photo.jpg, beside this script, and both windows show it.
# ffmpeg made it:
#   ffmpeg -f lavfi -i testsrc2=size=160x120:rate=1 -frames:v 1 -q:v 4 test_photo.jpg
#
# A later deb is the same build with another version, `-PappVersion=0.1.1`,
# which `:packaging:packageDeb` writes beside the first.
#
# Needs docker, or podman installed as docker.
# Usage:
#   tools/test_deb.sh [--temurin] [--upgrade <a later deb>] [--switch <another deb>] <the deb>
#   [image, ubuntu:20.04 by default]
set -euo pipefail

# Says why on the standard error and exits, with 1 or the status given.
die() {
    echo "$1" >&2
    exit "${2:-1}"
}

usage() {
    die "usage: $0 [--temurin] [--upgrade <a later deb>] [--switch <another deb>] <the deb> [image]" 2
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
    mounts+=(-v "$later:/deb/later/package.deb:ro" -v "$(dirname "$later"):/deb/later/beside:ro")
    shift 2
fi
if [[ "${1:-}" == --switch ]]; then
    (( $# >= 2 )) || usage
    other="$(realpath "$2")"
    [[ -f "$other" ]] || die "$other does not exist" 2
    mounts+=(-v "$other:/deb/switch/package.deb:ro" -v "$(dirname "$other"):/deb/switch/beside:ro")
    shift 2
fi
(( $# >= 1 && $# <= 2 )) || usage
deb="$(realpath "$1")"
image="${2:-ubuntu:20.04}"
[[ -f "$deb" ]] || die "$deb does not exist" 2
photo="$(dirname "$(realpath "$0")")/test_photo.jpg"
[[ -f "$photo" ]] || die "$photo does not exist"
command -v docker >/dev/null || die "Missing command: docker"

# What runs in the container, as root, with the deb at /deb/package.deb,
# the later one, if any, at /deb/later/package.deb, the other one, if any, at /deb/switch/package.deb,
# each one's folder at /deb/beside, /deb/later/beside and /deb/switch/beside, and the photo at /photo/test_photo.jpg.
docker run --rm -i -e "JRE=$jre" -v "$deb:/deb/package.deb:ro" -v "$(dirname "$deb"):/deb/beside:ro" \
    -v "$photo:/photo/test_photo.jpg:ro" "${mounts[@]}" "$image" sh -s <<'EOF'
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
# dpkg's state of the package $1, the package by default: installed, half-configured, config-files,
# not-installed and the like.
state() {
    dpkg-query -W -f='${db:Status-Status}' "${1:-$package}" 2>/dev/null || echo not-installed
}
is_installed() {
    [ "$(state "$1")" = installed ]
}
is_removed() {
    [ "$(state "$1")" = not-installed ] || [ "$(state "$1")" = config-files ]
}
is_version() {
    [ "$(dpkg-query -W -f='${Version}' "$package")" = "$1" ]
}
# The command $1 converts the photo to a PNG beside it, as the command line does with a photo alone,
# within two minutes.
converts() {
    rm -rf /tmp/photo && mkdir /tmp/photo && cp /photo/test_photo.jpg /tmp/photo/ &&
        timeout 120 "$1" /tmp/photo/test_photo.jpg &&
        [ "$(od -An -tx1 -N8 /tmp/photo/test_photo.dog.png | tr -d ' \n')" = 89504e470d0a1a0a ]
}
# The window $1, dog-vision-compose or dog-vision-swing, shows the photo in a window called Dog
# Vision on the display :99, which Xvfb draws in memory; the window's output is in /tmp/window.log,
# and its home is /tmp/home.
window_opens() {
    [ -e /tmp/.X11-unix/X99 ] || { Xvfb :99 -screen 0 1280x800x24 >/tmp/xvfb.log 2>&1 & }
    for _ in 1 2 3 4 5 6 7 8 9 10; do [ -e /tmp/.X11-unix/X99 ] && break; sleep 1; done
    rm -rf /tmp/home && mkdir /tmp/home
    DISPLAY=:99 HOME=/tmp/home "$1" /photo/test_photo.jpg >/tmp/window.log 2>&1 &
    window=$!
    shown=""
    for _ in $(seq 60); do
        if DISPLAY=:99 xwininfo -root -tree 2>/dev/null | grep -q '"Dog Vision"'; then
            shown=yes
            break
        fi
        kill -0 "$window" 2>/dev/null || break
        sleep 1
    done
    kill "$window" 2>/dev/null
    # A window still there ten seconds after SIGTERM is killed, so that the test cannot wait for it
    # for ever, and said to have stayed.
    for _ in $(seq 10); do kill -0 "$window" 2>/dev/null || break; sleep 1; done
    if kill -9 "$window" 2>/dev/null; then echo "note: $1 did not end on SIGTERM" >&3; fi
    wait "$window" 2>/dev/null
    [ -n "$shown" ] || tail -n 20 /tmp/window.log >&3
    [ -n "$shown" ]
}
# skiko and LWJGL loaded the libraries the deb installs rather than unpack their own copies, into
# the home or /tmp, as they do where no library path names them; FlatLaf, in dog-vision-swing,
# unpacked none of its own either.
unpacks_no_natives() {
    ! ls -d /tmp/home/.skiko /tmp/home/.lwjgl* /tmp/lwjgl* /tmp/home/.flatlaf* /tmp/flatlaf* 2>/dev/null |
        grep -q .
}
# The desktop entry $1 is valid, as desktop-file-validate has it, which is installed only once the
# package's own dependencies are; what it says is shown.
entry_is_valid() {
    command -v desktop-file-validate >/dev/null ||
        apt-get install -y -qq desktop-file-utils >/tmp/validate.log 2>&1 || tail -n 20 /tmp/validate.log >&3
    said="$(desktop-file-validate "$1" 2>&1)"
    status=$?
    [ -z "$said" ] || echo "$said" >&3
    return "$status"
}
# The desktop entry $1 runs dog-vision-web, which is on PATH and opens with xdg-open, on PATH too, a
# file that is there.
opens_the_page() {
    [ "$(sed -n 's/^Exec=//p' "$1")" = dog-vision-web ] && command -v dog-vision-web && command -v xdg-open &&
        set -- $(sed -n 's/^exec xdg-open //p' "$(command -v dog-vision-web)") && [ "$#" -eq 1 ] && [ -f "$1" ]
}
# The debs in the folder $2 of the project's packages that the deb $1 depends on at a version, one a
# line, of alternatives the first, as dog-vision-web of dog-vision-web-sourcemap's; it fails where
# one is not there.
beside() {
    dpkg-deb -f "$1" Depends | tr ',' '\n' | sed 's/ *|.*//' |
        sed -n 's/^ *\(dog-vision[a-z-]*\) (= \([^)]*\))$/\1_\2/p' | while read -r name; do
            ls "$2/${name}_"*.deb || return 1
        done
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
# The packages the deb $1 conflicts with, one a line, by name alone, as the project's debs name them.
conflicts_of() {
    dpkg-deb -f "$1" Conflicts | tr ',' '\n' | tr -d ' '
}
# None of the packages the deb $1 conflicts with is installed.
conflicts_removed() {
    for name in $(conflicts_of "$1"); do
        is_installed "$name" && return 1
    done
    return 0
}
# Each file of the deb $1 that is still there, and each folder in one of the project's, is an
# installed package's: dpkg removed what only the packages it conflicted with had, and handed over
# to the other deb what it replaces. Those that are no one's are shown.
no_strays() {
    strays="$(dpkg-deb -c "$1" | awk '{ print $6 }' | sed 's|^\.||' | grep -E '[^/]$|/dog-vision' |
        while read -r path; do
            if [ -e "$path" ] && ! dpkg -S "${path%/}" >/dev/null 2>&1; then echo "$path"; fi
        done)"
    [ -z "$strays" ] || echo "$strays" >&3
    [ -z "$strays" ]
}
# What each part of the package does once installed; $1 is added to each check's name.
installed_compose() {
    check "dog-vision-compose's window is in the desktop menu folder$1" \
        test -f /usr/share/applications/cz.loplex.dogvision.compose.desktop
    check "dog-vision-compose runs from PATH$1" dog-vision-compose --help
    command -v Xvfb >/dev/null || install_display
    check "dog-vision-compose's window opens under Xvfb$1" window_opens dog-vision-compose
    check "skiko and LWJGL load the deb's natives$1" unpacks_no_natives
}
installed_swing() {
    check "dog-vision-swing's window is in the desktop menu folder$1" \
        test -f /usr/share/applications/cz.loplex.dogvision.swing.desktop
    check "dog-vision-swing runs from PATH$1" dog-vision-swing --help
    command -v Xvfb >/dev/null || install_display
    check "dog-vision-swing's window opens under Xvfb$1" window_opens dog-vision-swing
    check "LWJGL and FlatLaf unpack no natives$1" unpacks_no_natives
}
installed_cli() {
    check "dog-vision-cli runs from PATH$1" dog-vision-cli --help
    check "it converts a JPEG to a PNG$1" converts dog-vision-cli
}
installed_common() {
    check "the shared JARs are in /usr/share/dog-vision-common/lib$1" \
        test -f /usr/share/dog-vision-common/lib/cli-jvm.jar
    check "/usr/bin has no dog-vision-common$1" test ! -e /usr/bin/dog-vision-common
}
installed_sourcemap() {
    check "the source map is beside the page's script$1" \
        test -f /usr/share/dog-vision-web/dog-vision.js.map -a -f /usr/share/dog-vision-web/dog-vision.js
}
installed_web() {
    check "the page is in the desktop menu folder$1" \
        test -f /usr/share/applications/cz.loplex.dogvision.web.desktop
    check "the page's desktop entry is valid$1" entry_is_valid /usr/share/applications/cz.loplex.dogvision.web.desktop
    check "the page's desktop entry opens it through dog-vision-web$1" \
        opens_the_page /usr/share/applications/cz.loplex.dogvision.web.desktop
    check "the page's script and style are beside it$1" \
        test -f /usr/share/dog-vision-web/dog-vision.js -a -f /usr/share/dog-vision-web/styles.css
}
# What each part leaves behind once removed: nothing of its own.
removed_compose() {
    check "dog-vision-compose's menu entry is gone" \
        test ! -e /usr/share/applications/cz.loplex.dogvision.compose.desktop
    check "dog-vision-compose's icons are gone" \
        test ! -e /usr/share/icons/hicolor/256x256/apps/cz.loplex.dogvision.compose.png
    check "/usr/bin/dog-vision-compose is gone" test ! -e /usr/bin/dog-vision-compose
    check "/usr/share/dog-vision-compose is gone" test ! -e /usr/share/dog-vision-compose
    check "/usr/lib/dog-vision-compose is gone" test ! -e /usr/lib/dog-vision-compose
}
removed_swing() {
    check "dog-vision-swing's menu entry is gone" test ! -e /usr/share/applications/cz.loplex.dogvision.swing.desktop
    check "dog-vision-swing's icons are gone" \
        test ! -e /usr/share/icons/hicolor/256x256/apps/cz.loplex.dogvision.swing.png
    check "/usr/bin/dog-vision-swing is gone" test ! -e /usr/bin/dog-vision-swing
    check "/usr/share/dog-vision-swing is gone" test ! -e /usr/share/dog-vision-swing
    check "/usr/lib/dog-vision-swing is gone" test ! -e /usr/lib/dog-vision-swing
}
removed_cli() {
    check "/usr/bin/dog-vision-cli is gone" test ! -e /usr/bin/dog-vision-cli
    check "/usr/share/dog-vision-cli is gone" test ! -e /usr/share/dog-vision-cli
}
removed_common() {
    check "/usr/share/dog-vision-common is gone" test ! -e /usr/share/dog-vision-common
}
removed_sourcemap() {
    check "the source map is gone" test ! -e /usr/share/dog-vision-web/dog-vision.js.map
    check "the page stays" test -f /usr/share/dog-vision-web/index.html
}
removed_web() {
    check "the page's menu entry is gone" test ! -e /usr/share/applications/cz.loplex.dogvision.web.desktop
    check "the page's icons are gone" test ! -e /usr/share/icons/hicolor/256x256/apps/cz.loplex.dogvision.web.png
    check "/usr/share/dog-vision-web is gone" test ! -e /usr/share/dog-vision-web
    check "/usr/bin/dog-vision-web is gone" test ! -e /usr/bin/dog-vision-web
}
# The parts of the package: dog-vision's are the packages it holds.
parts() {
    case "$package" in
        dog-vision) echo compose swing cli common web ;;
        dog-vision-compose | dog-vision-swing | dog-vision-cli | dog-vision-common | dog-vision-web)
            echo "${package#dog-vision-}"
            ;;
        dog-vision-web-sourcemap) echo sourcemap ;;
    esac
}
# What the package does once installed; $1 is added to each check's name.
check_installed() {
    command -v java >/dev/null && echo "note: java is $(readlink -f "$(command -v java)")"
    [ "$JRE" = temurin ] && check "no OpenJDK is installed beside Temurin$1" no_openjdk
    [ -n "$(parts)" ] || { echo "FAILED: no checks for the package $package"; failed=$((failed + 1)); }
    for part in $(parts); do "installed_$part" "$1"; done
}
check_removed() {
    for part in $(parts); do "removed_$part"; done
}

export DEBIAN_FRONTEND=noninteractive
# A mirror that stops answering fails each of apt's tries within a minute, and each download is
# tried four times.
printf 'Acquire::http::Timeout "60";\nAcquire::https::Timeout "60";\nAcquire::Retries "3";\n' \
    >/etc/apt/apt.conf.d/99test-timeouts
apt-get update -qq >/dev/null
[ -e /usr/share/applications ] && echo "note: the image has /usr/share/applications already"

if [ "$JRE" = temurin ]; then
    install_temurin || tail -n 20 /tmp/temurin.log
    check "Temurin's JRE installs from Adoptium's repository" dpkg-query -W temurin-25-jre
fi
apt-get install -y -qq /deb/package.deb $(beside /deb/package.deb /deb/beside) >/tmp/install.log 2>&1 ||
    tail -n 20 /tmp/install.log
check "$package installs and is configured" is_installed
check_installed ""

# The later deb's files replace the first one's, which has no maintainer scripts to run, and the
# menu entry passes from the one to the other.
if [ -e /deb/later/package.deb ]; then
    version="$(dpkg-deb -f /deb/later/package.deb Version)"
    apt-get install -y -qq /deb/later/package.deb $(beside /deb/later/package.deb /deb/later/beside) \
        >/tmp/upgrade.log 2>&1 || tail -n 20 /tmp/upgrade.log
    check "$package $version installs over it and is configured" is_installed
    check "the installed version is $version" is_version "$version"
    check_installed " after the upgrade"
fi

# Where the other deb conflicts with the package, apt removes the package to install it, with every
# package it conflicts with; where not, the package stays beside it. From here on, the other deb is
# the package.
staying=""
if [ -e /deb/switch/package.deb ]; then
    first="$package"
    first_deb=/deb/package.deb
    [ -e /deb/later/package.deb ] && first_deb=/deb/later/package.deb
    package="$(dpkg-deb -f /deb/switch/package.deb Package)"
    apt-get install -y -qq /deb/switch/package.deb $(beside /deb/switch/package.deb /deb/switch/beside) \
        >/tmp/switch.log 2>&1 || tail -n 20 /tmp/switch.log
    check "$package installs and is configured" is_installed
    check "no package it conflicts with stays installed" conflicts_removed /deb/switch/package.deb
    if ! conflicts_of /deb/switch/package.deb | grep -qx "$first"; then
        check "$first, which it does not conflict with, stays installed" is_installed "$first"
        staying="$first"
        # And does what it did.
        other="$package"
        package="$first"
        check_installed " beside $other"
        package="$other"
    fi
    check "of $first's files, only those an installed package owns are left" no_strays "$first_deb"
    check_installed " after the switch"
fi

# The package that stayed goes first, so that what it leaves is checked while the other is still
# there: the page the source map is for stays, dog-vision's.
if [ -n "$staying" ]; then
    apt-get remove -y -qq "$staying" >/tmp/remove-staying.log 2>&1 \
        || tail -n 20 /tmp/remove-staying.log
    check "$staying is removed" is_removed "$staying"
    other="$package"
    package="$staying"
    check_removed
    package="$other"
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
