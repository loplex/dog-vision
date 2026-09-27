#!/usr/bin/env bash
# Installs the desktop window's rpm in a bare container, runs its command line and removes it,
# checking each step.
#
# A bare image has no desktop, and openSUSE's has no /etc/xdg/menus, which is what a headless
# install for the command line alone meets. dnf or zypper installs the rpm there with its
# dependencies; rpm removes it. Each check says whether it held, and every check runs, so that one
# failing does not hide the others; the script fails if any did.
#
# Needs docker, or podman installed as docker. Usage:
#   tools/test_rpm.sh <the rpm> [image with dnf or zypper, fedora:42 by default]
set -euo pipefail

# Says why on the standard error and exits, with 1 or the status given.
die() {
    echo "$1" >&2
    exit "${2:-1}"
}

(( $# >= 1 && $# <= 2 )) || die "usage: $0 <the rpm> [image]" 2
rpm="$(realpath "$1")"
image="${2:-fedora:42}"
[[ -f "$rpm" ]] || die "$rpm does not exist" 2
command -v docker >/dev/null || die "Missing command: docker"

# What runs in the container, as root, with the rpm at /rpm/dog-vision.rpm.
docker run --rm -i -v "$rpm:/rpm/dog-vision.rpm:ro" "$image" sh -s <<'EOF'
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
    rpm -q dog-vision
}
is_removed() {
    ! rpm -q dog-vision
}
requires_no_xdg_utils() {
    ! rpm -qR dog-vision | grep -q xdg-utils
}
# rpm removes a folder of the package's with it, where it is empty.
owns_no_system_folder() {
    ! rpm -ql dog-vision | grep -qxE '/|/opt|/usr|/usr/share|/usr/share/applications|/usr/share/licenses'
}

[ -e /usr/share/applications ] && echo "note: the image has /usr/share/applications already"
[ -e /etc/xdg/menus ] && echo "note: the image has /etc/xdg/menus already"

if command -v zypper >/dev/null; then
    install_rpm() { zypper -q -n install --allow-unsigned-rpm /rpm/dog-vision.rpm; }
elif command -v dnf >/dev/null; then
    install_rpm() { dnf -q -y install /rpm/dog-vision.rpm; }
else
    echo "FAILED: the image has neither dnf nor zypper"
    exit 1
fi
# A scriptlet that fails leaves the package installed, and only the status tells.
install_rpm >/tmp/install.log 2>&1
status=$?
[ "$status" -eq 0 ] || tail -n 20 /tmp/install.log
check "the install exits with 0 (it exited with $status)" test "$status" -eq 0
check "dog-vision is installed" is_installed
check "it does not require xdg-utils" requires_no_xdg_utils
check "it owns no folder of the system's" owns_no_system_folder
check "the window is in the desktop menu folder" test -f /usr/share/applications/cz.loplex.dogvision.desktop
check "the command line runs" /opt/dog-vision/bin/dog-vision-cli --help

rpm -e dog-vision >/tmp/erase.log 2>&1
status=$?
[ "$status" -eq 0 ] || tail -n 20 /tmp/erase.log
check "rpm -e exits with 0 (it exited with $status)" test "$status" -eq 0
check "dog-vision is removed" is_removed
check "its menu entry is gone" test ! -e /usr/share/applications/cz.loplex.dogvision.desktop
check "/opt/dog-vision is gone" test ! -e /opt/dog-vision

if [ "$failed" -eq 0 ]; then
    echo "Every check held."
else
    echo "$failed check(s) failed."
    exit 1
fi
EOF
