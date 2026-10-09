#!/bin/sh
# Runs @NAME@ on the first @JAVA@ of:
# $JAVA_HOME's, the java on PATH, and the newest under /usr/lib/jvm and /usr/lib64/jvm.
# /usr/bin/java alone is not enough:
# the alternatives may point it at an older Java on a machine that has a newer one as well,
# or at a headless one beside a full one.
# Written by the build from its template in build-logic.

minimum=@MINIMUM@
# Whether @NAME@ opens a window, which a headless Java, one without AWT's X11 library, cannot.
window=@WINDOW@

# The home of the java at $1.
java_home() {
    dirname "$(dirname "$(readlink -f "$1")")"
}

# The feature version of the java at $1, 17 for 17.0.12 and 8 for 1.8.0_402:
# from its home's release file, or from what it prints as its version where there is none.
feature_version() {
    home=$(java_home "$1")
    if [ -r "$home/release" ]; then
        version=$(sed -n 's/^JAVA_VERSION="\(.*\)"$/\1/p' "$home/release")
    else
        version=$("$1" -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')
    fi
    case $version in
        1.*) version=${version#1.} ;;
    esac
    echo "${version%%[!0-9]*}"
}

# Whether the java at $1 runs, is Java @MINIMUM@ or newer, and has AWT's X11 library where @NAME@ opens a window.
suitable() {
    [ -x "$1" ] || return 1
    if [ "$window" = yes ] && [ ! -e "$(java_home "$1")/lib/libawt_xawt.so" ]; then
        return 1
    fi
    feature=$(feature_version "$1")
    case $feature in
        '' | *[!0-9]*) return 1 ;;
    esac
    [ "$feature" -ge "$minimum" ]
}

java=
if [ -n "$JAVA_HOME" ] && suitable "$JAVA_HOME/bin/java"; then
    java=$JAVA_HOME/bin/java
elif command -v java >/dev/null && suitable "$(command -v java)"; then
    java=$(command -v java)
else
    newest=0
    for candidate in /usr/lib/jvm/*/bin/java /usr/lib64/jvm/*/bin/java; do
        suitable "$candidate" || continue
        feature=$(feature_version "$candidate")
        if [ "$feature" -gt "$newest" ]; then
            newest=$feature
            java=$candidate
        fi
    done
fi
if [ -z "$java" ]; then
    echo "@NAME@ needs @JAVA@: none in JAVA_HOME, on PATH, in /usr/lib/jvm or /usr/lib64/jvm." >&2
    exit 1
fi

exec "$java" @JVM_OPTIONS@-cp '@CLASSPATH@' @MAIN_CLASS@ "$@"
