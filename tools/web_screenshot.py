"""Takes the documents' screenshot of the web page: the page with a photo open, in headless Chrome.

Usage: python3 -I tools/web_screenshot.py CHROME INDEX_HTML PHOTO OUT_PNG

Chrome is driven over its DevTools protocol through --remote-debugging-pipe, messages ended by NUL on the file
descriptors 3 and 4, so that no port is opened and no library is needed. The photo goes into the page's own file
input, as a choice in the file dialog would; the page is sized to WIDTH x HEIGHT, as the new headless mode keeps
part of the window for its own frame; the screenshot is taken once two captures in a row agree, so the photo has
been drawn.
"""

import base64
import json
import os
import subprocess
import sys
import tempfile
import time

WIDTH, HEIGHT = 1400, 820
TIMEOUT = 30.0
POLL = 0.5


class Chrome:
    """Headless Chrome and its DevTools pipe; [call] sends one command and returns its result."""

    def __init__(self, executable, profile):
        to_chrome, self.writer = os.pipe()
        self.reader, from_chrome = os.pipe()
        # Chrome reads commands on its fd 3 and writes answers on 4: the pipes are put there in the child.
        self.process = subprocess.Popen(
            [
                executable,
                "--headless=new",
                "--remote-debugging-pipe",
                f"--user-data-dir={profile}",
                f"--window-size={WIDTH},{HEIGHT}",
                "--lang=en",
                "--use-angle=swiftshader",
                "--enable-unsafe-swiftshader",
                "--no-first-run",
                "--hide-scrollbars",
                "about:blank",
            ],
            pass_fds=(3, 4),
            preexec_fn=lambda: (os.dup2(to_chrome, 3), os.dup2(from_chrome, 4)),
            env={**os.environ, "LANGUAGE": "en"},
        )
        os.close(to_chrome)
        os.close(from_chrome)
        self.count = 0
        self.pending = b""
        self.session = None

    def call(self, method, **params):
        self.count += 1
        message = {"id": self.count, "method": method, "params": params}
        if self.session:
            message["sessionId"] = self.session
        os.write(self.writer, json.dumps(message).encode() + b"\0")
        while True:
            answer = self.read()
            if answer.get("id") == self.count:
                if "error" in answer:
                    raise SystemExit(f"{method}: {answer['error']}")
                return answer["result"]

    def read(self):
        while b"\0" not in self.pending:
            chunk = os.read(self.reader, 1 << 16)
            if not chunk:
                raise SystemExit("Chrome closed its pipe")
            self.pending += chunk
        message, self.pending = self.pending.split(b"\0", 1)
        return json.loads(message)

    def evaluate(self, expression):
        return self.call("Runtime.evaluate", expression=expression, returnByValue=True)["result"].get("value")

    def close(self):
        # The pipe stays open until Chrome has gone, which otherwise logs its end as an error.
        self.session = None
        self.call("Browser.close")
        self.process.wait(timeout=10)
        os.close(self.writer)


def main():
    executable, index, photo, out = sys.argv[1:5]
    with tempfile.TemporaryDirectory() as profile:
        chrome = Chrome(executable, profile)
        page = next(t for t in chrome.call("Target.getTargets")["targetInfos"] if t["type"] == "page")
        chrome.session = chrome.call("Target.attachToTarget", targetId=page["targetId"], flatten=True)["sessionId"]
        chrome.call("Emulation.setDeviceMetricsOverride", width=WIDTH, height=HEIGHT, deviceScaleFactor=1, mobile=False)
        chrome.call("Page.navigate", url="file://" + os.path.abspath(index))
        wait_until("the page loaded", lambda: chrome.evaluate("document.readyState") == "complete")
        if not chrome.evaluate("!!document.createElement('canvas').getContext('webgl2')"):
            raise SystemExit("Chrome has no WebGL 2 here; the page would show nothing")
        document = chrome.call("DOM.getDocument")
        node = chrome.call("DOM.querySelector", nodeId=document["root"]["nodeId"], selector="#open")
        chrome.call("DOM.setFileInputFiles", files=[os.path.abspath(photo)], nodeId=node["nodeId"])
        shot = settled(lambda: base64.b64decode(chrome.call("Page.captureScreenshot", format="png")["data"]))
        with open(out, "wb") as file:
            file.write(shot)
        chrome.close()


def wait_until(what, condition):
    deadline = time.monotonic() + TIMEOUT
    while not condition():
        if time.monotonic() > deadline:
            raise SystemExit(f"{what} did not come within {TIMEOUT} s")
        time.sleep(POLL)


def settled(capture):
    """A capture equal to the one before it: the photo is drawn a moment after it is opened."""
    last = capture()
    deadline = time.monotonic() + TIMEOUT
    while time.monotonic() < deadline:
        time.sleep(POLL)
        shot = capture()
        if shot == last:
            return shot
        last = shot
    raise SystemExit(f"the page kept changing for {TIMEOUT} s")


main()
