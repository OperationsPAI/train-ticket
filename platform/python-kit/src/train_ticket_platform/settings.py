from __future__ import annotations

import json
import os
from datetime import timedelta
from pathlib import Path
from typing import Any, Mapping

SETTINGS_PATH_ENV = "SERVICE_SETTINGS_PATH"


def stated_settings(path: Path | str, env_var: str = SETTINGS_PATH_ENV) -> Mapping[str, Any]:
    # The file the service is deployed with, or the file an environment variable
    # points at. A path that cannot be read or a document that is not an object
    # yields nothing, and each caller's own default decides what the service does
    # then, so a release that states nothing usable is not a release that fails.
    configured = os.getenv(env_var, "").strip()
    source = Path(configured) if configured else Path(path)
    try:
        stated = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return stated if isinstance(stated, Mapping) else {}


def stated_non_negative_int(settings: Mapping[str, Any], key: str, default: int) -> int:
    # Zero is a value a key may state, a negative one is not: it is the shape of a
    # key that was never written for this document, so the default stands in.
    try:
        value = int(settings.get(key))
    except (TypeError, ValueError):
        return default
    return value if value >= 0 else default


def stated_positive_hours(settings: Mapping[str, Any], key: str, default: timedelta) -> timedelta:
    # A window of no hours is the same as a missing key here, so the default stands
    # in for both and the caller keeps the behaviour it was released with.
    try:
        hours = int(settings.get(key))
    except (TypeError, ValueError):
        return default
    return timedelta(hours=hours) if hours > 0 else default
