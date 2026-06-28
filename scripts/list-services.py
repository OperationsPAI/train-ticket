#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    catalog = json.loads((ROOT / "service-catalog.json").read_text(encoding="utf-8"))
    for service in catalog["platformModules"] + catalog["services"]:
        print(f"{service['id']}\t{service['language']}\t{service['phase']}\t{service['path']}")


if __name__ == "__main__":
    main()
