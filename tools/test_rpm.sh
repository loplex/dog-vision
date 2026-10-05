#!/usr/bin/env bash
# Installs an rpm of the project's,
# dog-vision-compose, dog-vision-swing, dog-vision-cli, dog-vision-common, dog-vision-web or dog-vision-web-sourcemap,
# in a bare container, runs it and removes it, checking each step.
# With --upgrade, it installs a later rpm over the first before removing it, as an update does.
# With --temurin, it installs Adoptium's Temurin JRE first,
# from Adoptium's repository, and checks that the rpm takes it rather than an OpenJDK.
#
# A bare image has no desktop, and openSUSE's has no /etc/xdg/menus,
# which is what a headless install for the command line alone meets.
# dnf or zypper installs the rpm there with its dependencies, a Java among them,
# and microdnf those its repositories have; rpm removes it.
# Each window is then opened under Xvfb,
# installed only once the rpm's own dependencies are, as tools/test_deb.sh does,
# and of dog-vision-web the desktop entry and the file it opens, as tools/test_deb.sh says.
# Each check says whether it held, and every check runs, so that one failing does not hide the others;
# the script fails if any did.
#
# An rpm of the project's that the rpm requires at its own version is installed with it from the rpm's own folder,
# as tools/test_deb.sh says.
#
# The command line converts test_photo.jpg, beside this script, and both windows show it, as tools/test_deb.sh says.
#
# A later rpm is the same build with another version, `-PappVersion=0.1.1`,
# which `:packaging:packageRpm` writes beside the first.
#
# Needs docker, or podman installed as docker.
# Usage:
#   tools/test_rpm.sh [--temurin] [--upgrade <a later rpm>] <the rpm> [image with dnf, zypper or
#   microdnf, fedora:42 by default]
set -euo pipefail

# Says why on the standard error and exits, with 1 or the status given.
die() {
    echo "$1" >&2
    exit "${2:-1}"
}

usage() {
    die "usage: $0 [--temurin] [--upgrade <a later rpm>] <the rpm> [image]" 2
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
    mounts+=(-v "$later:/rpm/later/package.rpm:ro" -v "$(dirname "$later"):/rpm/later/beside:ro")
    shift 2
fi
(( $# >= 1 && $# <= 2 )) || usage
rpm="$(realpath "$1")"
image="${2:-fedora:42}"
[[ -f "$rpm" ]] || die "$rpm does not exist" 2
photo="$(dirname "$(realpath "$0")")/test_photo.jpg"
[[ -f "$photo" ]] || die "$photo does not exist"
command -v docker >/dev/null || die "Missing command: docker"

# What runs in the container, as root, with the rpm at /rpm/package.rpm,
# the later one, if any, at /rpm/later/package.rpm, each one's folder at /rpm/beside and /rpm/later/beside,
# and the photo at /photo/test_photo.jpg.
docker run --rm -i -e "JRE=$jre" -v "$rpm:/rpm/package.rpm:ro" -v "$(dirname "$rpm"):/rpm/beside:ro" \
    -v "$photo:/photo/test_photo.jpg:ro" "${mounts[@]}" "$image" sh -s <<'EOF'
package="$(rpm -qp --qf '%{NAME}' /rpm/package.rpm)"
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
is_installed() {
    rpm -q "$package"
}
is_removed() {
    ! rpm -q "$package"
}
# One of the package, of version-release $1: rpm -i would put a second beside the first.
is_version() {
    [ "$(rpm -q --qf '%{VERSION}-%{RELEASE}\n' "$package")" = "$1" ]
}
requires_no_xdg_utils() {
    ! rpm -qR "$package" | grep -q xdg-utils
}
requires_xdg_utils() {
    rpm -qR "$package" | grep -qx xdg-utils
}
# rpm removes a folder of the package's with it, where it is empty.
owns_no_system_folder() {
    ! rpm -ql "$package" |
        grep -qxE '/|/opt|/usr|/usr/bin|/usr/share|/usr/share/applications|/usr/share/doc|/usr/share/licenses'
}
# The command $1 converts the photo to a PNG beside it, as the command line does with a photo alone.
converts() {
    rm -rf /tmp/photo && mkdir /tmp/photo && cp /photo/test_photo.jpg /tmp/photo/ &&
        "$1" /tmp/photo/test_photo.jpg &&
        [ "$(od -An -tx1 -N8 /tmp/photo/test_photo.dog.png | tr -d ' \n')" = 89504e470d0a1a0a ]
}
# The package's window, dog-vision-compose's or dog-vision-swing's, shows the photo in a window
# called Dog Vision on the display :99, which Xvfb draws in memory; the window's output is in
# /tmp/window.log, and its home is /tmp/home.
window_opens() {
    [ -e /tmp/.X11-unix/X99 ] || { Xvfb :99 -screen 0 1280x800x24 >/tmp/xvfb.log 2>&1 & }
    for _ in 1 2 3 4 5 6 7 8 9 10; do [ -e /tmp/.X11-unix/X99 ] && break; sleep 1; done
    rm -rf /tmp/home && mkdir /tmp/home
    DISPLAY=:99 HOME=/tmp/home "$package" /photo/test_photo.jpg >/tmp/window.log 2>&1 &
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
    wait "$window" 2>/dev/null
    [ -n "$shown" ] || tail -n 20 /tmp/window.log >&3
    [ -n "$shown" ]
}
# skiko and LWJGL loaded the libraries the rpm installs rather than unpack their own copies, into
# the home or /tmp, as they do where no library path names them; FlatLaf, in dog-vision-swing,
# unpacked none of its own either.
unpacks_no_natives() {
    ! ls -d /tmp/home/.skiko /tmp/home/.lwjgl* /tmp/lwjgl* /tmp/home/.flatlaf* /tmp/flatlaf* 2>/dev/null |
        grep -q .
}
# Installs the packages $@ with the image's own tool.
install_packages() {
    if command -v zypper >/dev/null; then
        zypper -q -n install "$@"
    elif command -v dnf >/dev/null; then
        dnf -q -y install "$@"
    else
        microdnf -y install "$@"
    fi
}
# Xvfb and xwininfo, for the window.
install_display() {
    install_packages xorg-x11-server-Xvfb xwininfo >/tmp/display.log 2>&1 ||
        install_packages xorg-x11-server-Xvfb xorg-x11-utils >>/tmp/display.log 2>&1 ||
        tail -n 20 /tmp/display.log
}
# The desktop entry $1 is valid, as desktop-file-validate has it, which is installed only once the
# package's own dependencies are; what it says is shown.
entry_is_valid() {
    command -v desktop-file-validate >/dev/null ||
        install_packages desktop-file-utils >/tmp/validate.log 2>&1 || tail -n 20 /tmp/validate.log >&3
    said="$(desktop-file-validate "$1" 2>&1)"
    status=$?
    [ -z "$said" ] || echo "$said" >&3
    return "$status"
}
# The desktop entry $1 runs xdg-open, which is on PATH, on a file that is there.
opens_a_file() {
    set -- $(sed -n 's/^Exec=//p' "$1")
    [ "$#" -eq 2 ] && [ "$1" = xdg-open ] && command -v xdg-open && [ -f "$2" ]
}
# Temurin's JRE from Adoptium's repository, as its instructions have it for each family.
install_temurin() {
    . /etc/os-release
    case "$ID" in
        fedora) repository="fedora/$VERSION_ID" ;;
        opensuse*) repository="opensuse/$VERSION_ID" ;;
        *) repository="rhel/${VERSION_ID%%.*}" ;;
    esac
    url="https://packages.adoptium.net/artifactory/rpm/$repository/$(uname -m)"
    if command -v zypper >/dev/null; then
        zypper -q -n addrepo -G "$url" adoptium >/tmp/temurin.log 2>&1
    else
        printf '%s\n' '[adoptium]' 'name=Adoptium' "baseurl=$url" 'enabled=1' 'gpgcheck=0' \
            >/etc/yum.repos.d/adoptium.repo
    fi
    install_packages temurin-25-jre >>/tmp/temurin.log 2>&1
}
no_openjdk() {
    ! rpm -qa | grep -q openjdk
}
# What the package does once installed; $1 is added to each check's name.
check_installed() {
    command -v java >/dev/null && echo "note: java is $(readlink -f "$(command -v java)")"
    [ "$JRE" = temurin ] && check "no OpenJDK is installed beside Temurin$1" no_openjdk
    case "$package" in
        dog-vision-compose)
            check "the window is in the desktop menu folder$1" \
                test -f /usr/share/applications/cz.loplex.dogvision.compose.desktop
            check "dog-vision-compose runs from PATH$1" dog-vision-compose --help
            command -v Xvfb >/dev/null || install_display
            check "its window opens under Xvfb$1" window_opens
            check "skiko and LWJGL load the rpm's natives$1" unpacks_no_natives
            ;;
        dog-vision-swing)
            check "the window is in the desktop menu folder$1" \
                test -f /usr/share/applications/cz.loplex.dogvision.swing.desktop
            check "dog-vision-swing runs from PATH$1" dog-vision-swing --help
            command -v Xvfb >/dev/null || install_display
            check "its window opens under Xvfb$1" window_opens
            check "LWJGL and FlatLaf unpack no natives$1" unpacks_no_natives
            ;;
        dog-vision-cli)
            check "dog-vision-cli runs from PATH$1" dog-vision-cli --help
            check "it converts a JPEG to a PNG$1" converts dog-vision-cli
            ;;
        dog-vision-common)
            check "its JARs are in /usr/share/dog-vision-common/lib$1" \
                test -f /usr/share/dog-vision-common/lib/cli-jvm.jar
            check "/usr/bin has nothing of its$1" test ! -e /usr/bin/dog-vision-common
            ;;
        dog-vision-web-sourcemap)
            check "the source map is beside the page's script$1" \
                test -f /usr/share/dog-vision-web/dog-vision.js.map -a -f /usr/share/dog-vision-web/dog-vision.js
            ;;
        dog-vision-web)
            check "the page is in the desktop menu folder$1" \
                test -f /usr/share/applications/cz.loplex.dogvision.web.desktop
            check "its desktop entry is valid$1" entry_is_valid /usr/share/applications/cz.loplex.dogvision.web.desktop
            check "its desktop entry opens the page with xdg-open$1" \
                opens_a_file /usr/share/applications/cz.loplex.dogvision.web.desktop
            check "the page's script and style are beside it$1" \
                test -f /usr/share/dog-vision-web/dog-vision.js -a -f /usr/share/dog-vision-web/styles.css
            check "/usr/bin has nothing of the page's$1" test ! -e /usr/bin/dog-vision-web
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
        dog-vision-compose)
            check "its menu entry is gone" test ! -e /usr/share/applications/cz.loplex.dogvision.compose.desktop
            check "its icons are gone" test ! -e /usr/share/icons/hicolor/256x256/apps/cz.loplex.dogvision.compose.png
            check "/usr/bin/dog-vision-compose is gone" test ! -e /usr/bin/dog-vision-compose
            check "/usr/share/dog-vision-compose is gone" test ! -e /usr/share/dog-vision-compose
            check "/usr/lib/dog-vision-compose is gone" test ! -e /usr/lib/dog-vision-compose
            ;;
        dog-vision-swing)
            check "its menu entry is gone" test ! -e /usr/share/applications/cz.loplex.dogvision.swing.desktop
            check "its icons are gone" test ! -e /usr/share/icons/hicolor/256x256/apps/cz.loplex.dogvision.swing.png
            check "/usr/bin/dog-vision-swing is gone" test ! -e /usr/bin/dog-vision-swing
            check "/usr/share/dog-vision-swing is gone" test ! -e /usr/share/dog-vision-swing
            check "/usr/lib/dog-vision-swing is gone" test ! -e /usr/lib/dog-vision-swing
            ;;
        dog-vision-cli)
            check "/usr/bin/dog-vision-cli is gone" test ! -e /usr/bin/dog-vision-cli
            check "/usr/share/dog-vision-cli is gone" test ! -e /usr/share/dog-vision-cli
            ;;
        dog-vision-common)
            check "/usr/share/dog-vision-common is gone" test ! -e /usr/share/dog-vision-common
            ;;
        dog-vision-web-sourcemap)
            check "the source map is gone" test ! -e /usr/share/dog-vision-web/dog-vision.js.map
            check "the page stays, dog-vision-web's" test -f /usr/share/dog-vision-web/index.html
            ;;
        dog-vision-web)
            check "its menu entry is gone" test ! -e /usr/share/applications/cz.loplex.dogvision.web.desktop
            check "its icons are gone" test ! -e /usr/share/icons/hicolor/256x256/apps/cz.loplex.dogvision.web.png
            check "/usr/share/dog-vision-web is gone" test ! -e /usr/share/dog-vision-web
            ;;
    esac
}

[ -e /usr/share/applications ] && echo "note: the image has /usr/share/applications already"
[ -e /etc/xdg/menus ] && echo "note: the image has /etc/xdg/menus already"

# The rpms in the folder $2 of the project's packages that the rpm $1 requires at a version, one a
# line; it fails where one is not there.
beside() {
    rpm -qpR "$1" | sed -n 's/^\(dog-vision[a-z-]*\) = \(.*\)$/\1-\2/p' | while read -r name; do
        ls "$2/$name".*.rpm || return 1
    done
}

# Each installs the rpms $@, or upgrades those installed to them.
if command -v zypper >/dev/null; then
    install_rpm() { zypper -q -n install --allow-unsigned-rpm "$@"; }
elif command -v dnf >/dev/null; then
    install_rpm() { dnf -q -y install "$@"; }
elif command -v microdnf >/dev/null; then
    # microdnf installs no file: what the rpms require, but one another, comes from the image's
    # repositories, one at a time, and the rpms themselves through rpm, past what none of them has.
    # Of a rich dependency, (A or B), the first alternative a repository has.
    install_rpm() {
        names="$(rpm -qp --qf '%{NAME}\n' "$@")"
        rpm -qpR "$@" | grep -v '^rpmlib(' | while IFS= read -r requirement; do
            echo "$names" | grep -qx "${requirement%% *}" || echo "$requirement"
        done >/tmp/requires
        while IFS= read -r requirement; do
            case "$requirement" in
                "("*) alternatives="$(echo "$requirement" | sed 's/^(//; s/)$//; s/ or /\n/g')" ;;
                *) alternatives="$requirement" ;;
            esac
            provided=""
            for alternative in $alternatives; do
                rpm -q --whatprovides "$alternative" && provided=yes && break
            done
            [ -n "$provided" ] && continue
            for alternative in $alternatives; do
                microdnf -y install "$alternative" && provided=yes && break
            done
            [ -n "$provided" ] || unavailable="$unavailable $requirement"
        done </tmp/requires
        if [ -z "$unavailable" ]; then
            rpm -U "$@"
        else
            rpm -U --nodeps "$@"
        fi
    }
else
    echo "FAILED: the image has none of dnf, zypper and microdnf"
    exit 1
fi
unavailable=""
if [ "$JRE" = temurin ]; then
    install_temurin || tail -n 20 /tmp/temurin.log
    check "Temurin's JRE installs from Adoptium's repository" rpm -q temurin-25-jre
fi
# A scriptlet that fails leaves the package installed, and only the status tells.
install_rpm /rpm/package.rpm $(beside /rpm/package.rpm /rpm/beside) >/tmp/install.log 2>&1
status=$?
[ "$status" -eq 0 ] || tail -n 20 /tmp/install.log
[ -n "$unavailable" ] && echo "note: installed without what no repository has:$unavailable"
check "the install exits with 0 (it exited with $status)" test "$status" -eq 0
check "$package is installed" is_installed
if [ "$package" = dog-vision-web ]; then
    check "it requires xdg-utils, for xdg-open" requires_xdg_utils
else
    check "it does not require xdg-utils" requires_no_xdg_utils
fi
check "it owns no folder of the system's" owns_no_system_folder
check_installed ""

# The later rpm's files replace the first one's, which has no scriptlets to run, and the menu entry
# passes from the one to the other.
if [ -e /rpm/later/package.rpm ]; then
    version="$(rpm -qp --qf '%{VERSION}-%{RELEASE}' /rpm/later/package.rpm)"
    install_rpm /rpm/later/package.rpm $(beside /rpm/later/package.rpm /rpm/later/beside) >/tmp/upgrade.log 2>&1
    status=$?
    [ "$status" -eq 0 ] || tail -n 20 /tmp/upgrade.log
    check "the upgrade to $version exits with 0 (it exited with $status)" test "$status" -eq 0
    check "the one $package installed is $version" is_version "$version"
    check_installed " after the upgrade"
fi

rpm -e "$package" >/tmp/erase.log 2>&1
status=$?
[ "$status" -eq 0 ] || tail -n 20 /tmp/erase.log
check "rpm -e exits with 0 (it exited with $status)" test "$status" -eq 0
check "$package is removed" is_removed
check_removed

if [ "$failed" -eq 0 ]; then
    echo "Every check held."
else
    echo "$failed check(s) failed."
    exit 1
fi
EOF
