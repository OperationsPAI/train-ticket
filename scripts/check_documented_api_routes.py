#!/usr/bin/env python3
"""Verify that the implemented-API index points at real route registrations."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INDEX = ROOT / "docs/03-ddd-final/domain-reduce-status.md"
HTTP_METHODS = "GET|POST|PUT|PATCH|DELETE"


def normalize_route(route: str) -> str:
    parts = []
    for part in route.strip().split("/"):
        if not part:
            continue
        if part.startswith(":") or (part.startswith("{") and part.endswith("}")):
            parts.append("{}")
        else:
            parts.append(part)
    return "/" + "/".join(parts)


def routes_in_source(path: Path) -> set[tuple[str, str]]:
    text = path.read_text(encoding="utf-8", errors="replace")
    routes: set[tuple[str, str]] = set()

    if path.suffix == ".java":
        prefix = ""
        class_mapping = re.search(
            r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"', text
        )
        if class_mapping:
            prefix = class_mapping.group(1)
        mapping = re.compile(
            r'@(Get|Post|Put|Patch|Delete)Mapping'
            r'(?:\(\s*(?:value\s*=\s*)?"([^"]*)"[^)]*\))?',
            re.DOTALL,
        )
        for method, suffix in mapping.findall(text):
            routes.add((method.upper(), normalize_route(prefix + suffix)))
    elif path.suffix == ".go":
        groups: dict[str, str] = {}
        for variable, parent, prefix in re.findall(
            r'(\w+)\s*:=\s*(\w+)\.Group\("([^"]+)"\)', text
        ):
            groups[variable] = groups.get(parent, "") + prefix
        for variable, method, suffix in re.findall(
            rf'(\w+)\.({HTTP_METHODS})\("([^"]+)"', text
        ):
            routes.add((method, normalize_route(groups.get(variable, "") + suffix)))
    elif path.suffix == ".rs":
        for route, method in re.findall(
            rf'\.route\(\s*"([^"]+)"\s*,\s*({HTTP_METHODS.lower().replace("|", "|")})\s*\(',
            text,
            re.DOTALL,
        ):
            routes.add((method.upper(), normalize_route(route)))
    elif path.suffix == ".py":
        prefixes = dict(
            re.findall(
                r'(\w+)\s*=\s*APIRouter\([^)]*prefix\s*=\s*"([^"]+)"',
                text,
                re.DOTALL,
            )
        )
        for variable, method, suffix in re.findall(
            r'@(\w+)\.(get|post|put|patch|delete)\(\s*"([^"]+)"', text
        ):
            routes.add(
                (method.upper(), normalize_route(prefixes.get(variable, "") + suffix))
            )
    elif path.suffix == ".ts":
        direct = re.compile(
            r'\b(?:app|server)\.(get|post|put|patch|delete)'
            r'(?:\s*<[\s\S]*?>)?\s*\(\s*"([^"]+)"'
        )
        for method, route in direct.findall(text):
            routes.add((method.upper(), normalize_route(route)))
        for method, route in re.findall(
            rf'stateChanging\(\s*(?:app|server)\s*,[^,]+,\s*"({HTTP_METHODS})"'
            r'\s*,\s*"([^"]+)"',
            text,
            re.DOTALL,
        ):
            routes.add((method, normalize_route(route)))
    return routes


def documented_routes() -> list[tuple[int, str, str, str, list[Path]]]:
    rows = []
    for line_number, line in enumerate(INDEX.read_text(encoding="utf-8").splitlines(), 1):
        if not line.startswith("| ") or "](" not in line or "`" not in line:
            continue
        columns = [column.strip() for column in line.strip().strip("|").split("|")]
        if len(columns) != 4 or columns[0] in {"领域", "---"}:
            continue
        endpoints = re.findall(rf'`(({HTTP_METHODS})\s+[^`]+)`', columns[2])
        sources = [
            (INDEX.parent / relative).resolve()
            for relative in re.findall(r'\]\(([^)]+)\)', columns[3])
        ]
        for endpoint, method in endpoints:
            _, route = endpoint.split(None, 1)
            rows.append(
                (line_number, columns[0], method, normalize_route(route), sources)
            )
    return rows


def validate() -> list[str]:
    errors: list[str] = []
    rows = documented_routes()
    if not rows:
        return ["no implemented API examples found in the domain ownership index"]

    for line_number, domain, method, route, sources in rows:
        missing_sources = [path for path in sources if not path.is_file()]
        if missing_sources:
            for path in missing_sources:
                errors.append(
                    f"line {line_number} {domain}: missing source {path.relative_to(ROOT)}"
                )
            continue
        implemented = set().union(*(routes_in_source(path) for path in sources))
        if (method, route) not in implemented:
            relative_sources = ", ".join(str(path.relative_to(ROOT)) for path in sources)
            errors.append(
                f"line {line_number} {domain}: {method} {route} not registered in "
                f"{relative_sources}"
            )
    return errors


def main() -> int:
    errors = validate()
    if errors:
        print(f"documented-api-routes: {len(errors)} error(s)")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"documented-api-routes: OK ({len(documented_routes())} examples verified)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
