from __future__ import annotations

import json
import os
from collections.abc import Iterable
from pathlib import Path
from typing import Any, Mapping

CONFIG_DIR_ENV = "FARE_PRICING_CONFIG_DIR"
CONTRACTS_ENV = "FARE_PRICING_RESOLUTION_CONTRACTS"
RESOLUTION_DOCUMENT = "resolution.json"
SHIPPED_CONFIG_DIR = Path(__file__).resolve().parents[2] / "config"


def config_dir() -> Path:
    """The directory this process reads its settings from.

    The image states where the deployment keeps them; a checkout reads the
    directory beside the service's sources, which holds the same document.
    """
    return Path(os.environ.get(CONFIG_DIR_ENV) or SHIPPED_CONFIG_DIR)


def read_settings(document: str) -> Mapping[str, Any]:
    """The settings document this process was started with, or an empty one.

    A document that is absent or unreadable is not an error: the caller then
    keeps the default it compiles, which is what a bare process and a unit test
    both get.
    """
    try:
        loaded = json.loads((config_dir() / document).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return loaded if isinstance(loaded, Mapping) else {}


def _contracts(names: Iterable[object]) -> frozenset[str]:
    """The contract names in a stated list, with the blank ones dropped."""
    return frozenset(name.strip() for name in names if isinstance(name, str) and name.strip())


def served_contracts() -> frozenset[str] | None:
    """The supplier contracts this deployment serves its rule sets under.

    An agreement cites the rule set a quote was priced on, and the rule set
    belongs to the contract its supplier is carried under, so a deployment
    states the contracts its catalogue carries in `config/resolution.json`. The
    environment overlays the list, so a release that carries a different
    catalogue states it without a build. A process that states neither serves
    every contract it holds, which is what a bare checkout and a unit test both
    get.
    """
    stated = _contracts(os.environ.get(CONTRACTS_ENV, "").split(","))
    if stated:
        return stated
    section = read_settings(RESOLUTION_DOCUMENT).get("resolution")
    if not isinstance(section, Mapping):
        return None
    declared = section.get("servedContracts")
    if not isinstance(declared, list):
        return None
    return _contracts(declared) or None
