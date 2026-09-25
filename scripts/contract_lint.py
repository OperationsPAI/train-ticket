#!/usr/bin/env python3
"""Mechanical contract-conformance checks for docs/08-contracts/.

Turns the review rules that keep getting violated into CI:
  C1  Money on the wire is {currency, minorUnits} — monetary objects must not
      use the JSON keys "units"/"cents". Non-monetary capacity `units` are valid.
  C2  Wire-boundary files (anything that names a Redis stream or XADDs)
      must not serialize `sourceCommandId` — the contract envelope has
      exactly 8 fields (shared-primitives.md §1). Domain-internal event
      metadata may keep it; the bus adapter must strip it.
  C3  Stream literals `events:<context>` may appear only at a messaging or
      composition boundary — stream naming does not belong in domain or
      application policy.

False positives can be suppressed by listing `<check-id> <path>` lines in
scripts/contract-lint-ignore.txt.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC_EXT = {".java", ".go", ".py", ".rs", ".ts"}
STREAM_RE = re.compile(r"events:[a-z][a-z-]+")
STREAM_LITERAL_RE = re.compile(r"(['\"])events:[a-z][a-z-]+\1")
XADD_RE = re.compile(r"\bxadd\b|\bXADD\b|\bXAdd\b|xAdd")
JSON_KEY_RE = re.compile(r'(["\'`])(units|cents)\1\s*[:=]')
CURRENCY_KEY_RE = re.compile(r'(["\'`])currency\1\s*[:=]')
SOURCE_COMMAND_KEY_RE = re.compile(
    r'(["\'`])sourceCommandId\1\s*[:=]|json:\"sourceCommandId(?:,omitempty)?\"'
)
BOUNDARY_PARTS = {"adapters", "infrastructure", "config"}
BOUNDARY_STEMS = {"bootstrap", "main", "messaging", "subscriber"}


def load_ignores() -> set[tuple[str, str]]:
    path = ROOT / "scripts" / "contract-lint-ignore.txt"
    entries: set[tuple[str, str]] = set()
    if path.is_file():
        for line in path.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                check, _, rel = line.partition(" ")
                entries.add((check, rel.strip()))
    return entries


def is_test(path: Path) -> bool:
    s = str(path)
    return (
        "test" in path.name.lower()
        or "/tests/" in s
        or s.endswith("_test.go")
    )


def is_messaging_boundary(path: Path) -> bool:
    return bool(BOUNDARY_PARTS.intersection(path.parts)) or path.stem in BOUNDARY_STEMS


def money_key_violations(lines: list[str]) -> list[int]:
    violations: list[int] = []
    for index, line in enumerate(lines):
        match = JSON_KEY_RE.search(line)
        if not match:
            continue
        key = match.group(2)
        if key == "cents":
            violations.append(index + 1)
            continue
        window = "\n".join(lines[max(0, index - 6) : index + 7])
        if CURRENCY_KEY_RE.search(window):
            violations.append(index + 1)
    return violations


def main() -> int:
    ignores = load_ignores()
    violations: list[str] = []

    def report(check: str, path: Path, line_no: int, message: str) -> None:
        rel = str(path.relative_to(ROOT))
        if (check, rel) in ignores:
            return
        violations.append(f"[{check}] {rel}:{line_no} — {message}")

    for path in sorted((ROOT / "services").rglob("*")):
        if path.suffix not in SRC_EXT or not path.is_file():
            continue
        s = str(path)
        if "node_modules" in s or "/target/" in s or "/.venv/" in s:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        lines = text.splitlines()

        if not is_test(path):
            for i in money_key_violations(lines):
                report(
                    "C1-money-minorunits", path, i,
                    'Money wire shape is {currency, minorUnits}; keys '
                    '"units"/"cents" are forbidden (shared-primitives §3)',
                )

        is_wire = bool(STREAM_RE.search(text) or XADD_RE.search(text))
        if is_wire and not is_test(path) and SOURCE_COMMAND_KEY_RE.search(text):
            for i, line in enumerate(lines, 1):
                if SOURCE_COMMAND_KEY_RE.search(line):
                    report(
                        "C2-envelope-8-fields", path, i,
                        "wire-boundary file serializes sourceCommandId; "
                        "the bus envelope has exactly the 8 contract "
                        "fields (shared-primitives §1)",
                    )

        if not is_messaging_boundary(path) and not is_test(path):
            for i, line in enumerate(lines, 1):
                if STREAM_LITERAL_RE.search(line):
                    report(
                        "C3-stream-scope", path, i,
                        "stream literal outside an adapters/ path; stream "
                        "naming belongs to the messaging adapter layer",
                    )

    if violations:
        print(f"contract-lint: {len(violations)} violation(s)")
        for v in violations:
            print("  " + v)
        return 1
    print("contract-lint: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
