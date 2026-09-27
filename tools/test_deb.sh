#!/usr/bin/env bash
# Installs the desktop window's deb in a bare container, runs its command line and removes it,
# checking each step.
#
# A bare image has no desktop and none of its folders, /usr/share/applications among them, which
# is what a headless install for the command line alone meets. apt installs the deb there with its
# dependencies. Each check says whether it held, and every check runs, so that one failing does not
# hide the others; the script fails if any did.
#
# Needs docker, or podman installed as docker. Usage:
#   tools/test_deb.sh <the deb> [image, ubuntu:20.04 by default]
set -euo pipefail

# Says why on the standard error and exits, with 1 or the status given.
die() {
    echo "$1" >&2
    exit "${2:-1}"
}

(( $# >= 1 && $# <= 2 )) || die "usage: $0 <the deb> [image]" 2
deb="$(realpath "$1")"
image="${2:-ubuntu:20.04}"
[[ -f "$deb" ]] || die "$deb does not exist" 2
command -v docker >/dev/null || die "Missing command: docker"

# What runs in the container, as root, with the deb at /deb/dog-vision.deb.
docker run --rm -i -v "$deb:/deb/dog-vision.deb:ro" "$image" sh -s <<'EOF'
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
    dpkg-query -W -f='${db:Status-Status}' dog-vision 2>/dev/null || echo not-installed
}
is_installed() {
    [ "$(state)" = installed ]
}
is_removed() {
    [ "$(state)" = not-installed ] || [ "$(state)" = config-files ]
}

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
[ -e /usr/share/applications ] && echo "note: the image has /usr/share/applications already"

apt-get install -y -qq /deb/dog-vision.deb >/tmp/install.log 2>&1 || tail -n 20 /tmp/install.log
check "dog-vision installs and is configured" is_installed
check "the window is in the desktop menu folder" test -f /usr/share/applications/cz.loplex.dogvision.desktop
check "the command line runs" /opt/dog-vision/bin/dog-vision-cli --help

apt-get remove -y -qq dog-vision >/tmp/remove.log 2>&1 || tail -n 20 /tmp/remove.log
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
