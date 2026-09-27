"""Check that every relative link in the repository's Markdown resolves, down to its #anchor.

Run it from anywhere with python3 tools/check_links.py; it exits non-zero and lists each broken
link. Links to other sites are left alone: they fail for reasons that have nothing to do with a
change here. Anchors are GitHub's: a heading in lower case, without punctuation, spaces as hyphens.
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
FENCE = re.compile(r"^```.*?^```", re.MULTILINE | re.DOTALL)


def anchors(markdown: str) -> set[str]:
    """The anchors GitHub gives the headings, with -1, -2 for headings that repeat."""
    seen: dict[str, int] = {}
    result = set()
    for heading in re.findall(r"^#+ (.+)$", FENCE.sub("", markdown), re.MULTILINE):
        slug = re.sub(r"[^\w\- ]", "", heading.strip().lower()).replace(" ", "-")
        count = seen.get(slug, 0)
        seen[slug] = count + 1
        result.add(slug if count == 0 else f"{slug}-{count}")
    return result


def broken(path: Path) -> list[str]:
    text = FENCE.sub("", path.read_text(encoding="utf-8"))
    problems = []
    for target in LINK.findall(text):
        if re.match(r"[a-z]+:", target):
            continue
        file, _, anchor = target.partition("#")
        linked = (path.parent / file).resolve() if file else path
        if not linked.exists():
            problems.append(f"{path.relative_to(ROOT)}: {target}: no such file")
        elif anchor and linked.suffix == ".md" and anchor not in anchors(linked.read_text(encoding="utf-8")):
            problems.append(f"{path.relative_to(ROOT)}: {target}: no such heading")
    return problems


def main() -> int:
    files = subprocess.run(["git", "ls-files", "*.md"], cwd=ROOT, capture_output=True, text=True, check=True)
    problems = [p for name in files.stdout.split() for p in broken(ROOT / name)]
    print("\n".join(problems) or "Every relative link resolves.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
