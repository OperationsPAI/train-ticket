from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from train_ticket_platform.documents import document_path, document_section, read_document

CATALOG_FILE = Path(__file__).resolve().parents[1] / "config" / "release-catalog.json"
CATALOG_PATH_VARIABLE = "REPORTING_RELEASE_CATALOG"
METRIC_SECTION = "metrics"
DASHBOARD_SECTION = "dashboards"


@dataclass(frozen=True, slots=True)
class ReleaseCatalog:
    """The documents one release catalog declares, in the order the file lists them.

    `revision` is the release the catalog was cut from.
    """

    revision: str
    metrics: tuple[Mapping[str, Any], ...]
    dashboards: tuple[Mapping[str, Any], ...]


def catalog_file() -> Path:
    """The release catalog this build ships, which a deployment may point elsewhere."""
    return document_path(CATALOG_FILE, CATALOG_PATH_VARIABLE)


def load_release_catalog(path: Path | None = None) -> ReleaseCatalog:
    """The metric and dashboard documents the release catalog declares.

    Entries are returned as the file wrote them: the document holds the shape
    the snapshot stores keep, so no translation sits between the catalog and the
    store, and a value the catalog states is stored as stated. Both sections
    state the same publication state, so one vocabulary describes what the
    release published.
    """
    document = read_document(path or catalog_file())
    return ReleaseCatalog(
        revision=str(document.get("revision", "")),
        metrics=document_section(document, METRIC_SECTION),
        dashboards=document_section(document, DASHBOARD_SECTION),
    )
