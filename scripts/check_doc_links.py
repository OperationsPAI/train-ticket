#!/usr/bin/env python3
"""Validate the local Markdown links and anchors of the repository's documents.

A link into a deleted section is the failure mode this catches: the file exists,
the anchor does not, and nothing else in the check suite notices. It walks every
Markdown file that ships with the release, resolves each relative link against
the file that made it, and requires an in-file anchor to exist in the target.

External links (http, https, mailto) are not fetched; this is a static check.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Directories that hold vendored or build output: their READMEs are not ours.
SKIP_PARTS = {"node_modules", "target", ".venv", "dist", "test-output", ".git", "__pycache__"}


def is_source(path: Path) -> bool:
    return not any(part in SKIP_PARTS for part in path.relative_to(ROOT).parts)


# Every Markdown file the repository ships: the docs tree, the deployment and
# platform guides, and one README per service.
def documents() -> list[Path]:
    files = sorted(p for p in ROOT.glob("docs/**/*.md") if is_source(p))
    files += [ROOT / "README.md"]
    files += sorted(p for p in ROOT.glob("deploy/**/*.md") if is_source(p))
    files += sorted(p for p in ROOT.glob("platform/**/*.md") if is_source(p))
    files += sorted(p for p in ROOT.glob("services/*/README.md") if is_source(p))
    files += sorted(p for p in ROOT.glob(".devcontainer/*.md") if is_source(p))
    return [f for f in files if f.is_file()]


LINK = re.compile(r"\[(?P<text>[^\]]*)\]\((?P<target>[^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE = re.compile(r"^\s*(```|~~~)")
HEADING = re.compile(r"^#{1,6}\s+(?P<title>.*?)\s*#*\s*$")


def slug(title: str) -> str:
    """GitHub's heading anchor: lowercase, punctuation dropped, spaces to dashes."""
    text = title.strip().lower()
    text = re.sub(r"[^\w\s\-]", "", text, flags=re.UNICODE)
    text = text.replace(" ", "-")
    return text


def anchors_of(path: Path) -> set[str]:
    found: set[str] = set()
    in_fence = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        match = HEADING.match(line)
        if match:
            found.add(slug(match.group("title")))
    return found


def links_of(path: Path) -> list[tuple[int, str]]:
    found: list[tuple[int, str]] = []
    in_fence = False
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        for match in LINK.finditer(line):
            found.append((number, match.group("target")))
    return found


def main() -> int:
    anchors: dict[Path, set[str]] = {}
    failures: list[str] = []
    checked = 0
    for document in documents():
        for number, target in links_of(document):
            if "://" in target or target.startswith("mailto:"):
                continue
            path_part, _, anchor = target.partition("#")
            if not path_part:
                destination = document
            else:
                destination = (document.parent / path_part).resolve()
                if not destination.exists():
                    failures.append(
                        f"{document.relative_to(ROOT)}:{number}: link target does not exist: {target}"
                    )
                    continue
                if destination.is_dir():
                    # A directory link is what GitHub shows as a listing; it does
                    # not need a README to be a valid target.
                    checked += 1
                    continue
            checked += 1
            if not anchor:
                continue
            if destination.suffix != ".md":
                continue
            if destination not in anchors:
                anchors[destination] = anchors_of(destination)
            if anchor.lower() not in anchors[destination]:
                failures.append(
                    f"{document.relative_to(ROOT)}:{number}: "
                    f"anchor not found in {destination.relative_to(ROOT)}: #{anchor}"
                )
    if failures:
        print("doc link check failed:")
        for failure in failures:
            print("  " + failure)
        return 1
    print(f"doc link check passed: {len(documents())} documents, {checked} local links")
    return 0


if __name__ == "__main__":
    sys.exit(main())
