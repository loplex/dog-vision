#!/usr/bin/env bash
# Serves the web page as built, web/build/dist/js/productionExecutable,
# which `./gradlew :web:jsBrowserDistribution` builds, on http://localhost:<port>/ with Python's http.server,
# and opens it in the default browser, until Ctrl+C.
# A page from localhost may use the camera, which a browser allows only to a secure origin.
#
# Needs python3, and xdg-open unless --no-browser.
# Run it from anywhere:
#   tools/run_web_on_linux.sh [--port <port>, 8000 by default] [--no-browser]
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

die() {
    echo "$1" >&2
    exit "${2:-1}"
}

usage() {
    die "usage: $0 [--port <port>] [--no-browser]" 2
}

port=8000
browser=yes
while (( $# > 0 )); do
    case "$1" in
        --port) [[ "${2:-}" =~ ^[0-9]+$ ]] || usage; port="$2"; shift 2 ;;
        --no-browser) browser=no; shift ;;
        *) usage ;;
    esac
done

page="$root/web/build/dist/js/productionExecutable"
[[ -f "$page/index.html" ]] || die "No $page/index.html: ./gradlew :web:jsBrowserDistribution builds it"
command -v python3 >/dev/null || die "Missing python3"
[[ "$browser" == no ]] || command -v xdg-open >/dev/null || die "Missing xdg-open: --no-browser serves the page alone"

# Python binds the port before it opens the browser,
# so that a port in use fails here rather than opening whatever already answers on it.
exec python3 -c '
import functools, http.server, subprocess, sys
page, port, browser = sys.argv[1], int(sys.argv[2]), sys.argv[3] == "yes"
handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=page)
try:
    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), handler)
except OSError as error:
    sys.exit(f"Cannot serve on port {port}: {error.strerror}; --port takes another")
url = f"http://localhost:{port}/"
print(f"Serving {url}, Ctrl+C stops it", file=sys.stderr, flush=True)
if browser:
    subprocess.Popen(["xdg-open", url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    server.serve_forever()
except KeyboardInterrupt:
    pass
' "$page" "$port" "$browser"
