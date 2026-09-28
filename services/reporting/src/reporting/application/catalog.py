from __future__ import annotations

import json
from collections.abc import Mapping
from pathlib import Path
from typing import Any

# The catalog the governance review signs off is shipped beside the package, so
# the definitions the read endpoints answer from are the ones the release was
# checked against. It holds the document `metric_definition_snapshots` keeps,
# which is why no translation sits between the file and the store.
CATALOG_FILE = Path(__file__).resolve().parents[1] / "config" / "metric-catalog.json"


def catalog_file() -> Path:
    """The catalog this build ships, which a deployment may point elsewhere."""
    return CATALOG_FILE


def _document(path: Path | None = None) -> Mapping[str, Any]:
    """The catalog document, or an empty one when nothing is shipped there."""
    source = path or CATALOG_FILE
    if not source.is_file():
        return {}
    document = json.loads(source.read_text(encoding="utf-8"))
    return document if isinstance(document, Mapping) else {}


def catalog_revision(path: Path | None = None) -> str:
    """The revision the catalog states, so a seed can be read back against it.

    An absent catalog states nothing rather than a placeholder, because a
    revision that was never signed off is not one a log line should report.
    """
    return str(_document(path).get("revision", ""))


def load_metric_catalog(path: Path | None = None) -> tuple[Mapping[str, Any], ...]:
    """The metric documents the catalog publishes, in the order it lists them.

    A catalog that is absent or that declares no metrics seeds nothing, which is
    the shape a checkout without the governance catalog runs with. Entries are
    returned as written: the file carries the stored document, and an entry
    without an id cannot be addressed in the store so it is skipped here rather
    than inserted under a name nothing can read back.
    """
    entries = _document(path).get("metrics")
    if not isinstance(entries, list):
        return ()
    return tuple(
        dict(entry)
        for entry in entries
        if isinstance(entry, Mapping) and str(entry.get("metricId", "")).strip()
    )
