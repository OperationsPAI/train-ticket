from __future__ import annotations

from collections.abc import Mapping, Sequence
import json
import os
from pathlib import Path
from typing import Any


def document_path(default: Path, variable: str, environ: Mapping[str, str] | None = None) -> Path:
    """The document this build ships, which a deployment may point elsewhere.

    The override exists so an operator can correct a shipped document without
    rebuilding the image, and the default keeps a checkout and the image reading
    the same path.
    """
    source = environ if environ is not None else os.environ
    override = source.get(variable, "").strip()
    return Path(override) if override else default


def read_document(path: Path) -> dict[str, Any]:
    """Read a document a service ships, or report the path that could not be read.

    A missing document is an error rather than an empty mapping: a service whose
    document did not travel with it would otherwise start with nothing to seed
    and answer its reads from an empty store.
    """
    if not path.is_file():
        raise FileNotFoundError(f"shipped document is missing: {path}")
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError(f"shipped document is not an object: {path}")
    return document


def document_section(document: Mapping[str, Any], section: str) -> tuple[Mapping[str, Any], ...]:
    """The entries one section of a shipped document declares, in the order it lists them.

    A section the document does not declare is an empty tuple, which is what a
    build without that section runs with, and an entry that is not an object is
    skipped here rather than written under a name nothing can read back.
    """
    entries = document.get(section)
    if isinstance(entries, (str, bytes)) or not isinstance(entries, Sequence):
        return ()
    return tuple(dict(entry) for entry in entries if isinstance(entry, Mapping))
