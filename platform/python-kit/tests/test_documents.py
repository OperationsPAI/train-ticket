from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from train_ticket_platform.documents import document_path, document_section, read_document


def test_a_deployment_may_point_at_another_document(tmp_path: Path) -> None:
    default = tmp_path / "shipped.json"
    override = tmp_path / "corrected.json"
    assert document_path(default, "EXAMPLE_DOCUMENT", {}) == default
    assert document_path(default, "EXAMPLE_DOCUMENT", {"EXAMPLE_DOCUMENT": str(override)}) == override
    assert document_path(default, "EXAMPLE_DOCUMENT", {"EXAMPLE_DOCUMENT": "   "}) == default


def test_a_document_is_read_as_written(tmp_path: Path) -> None:
    path = tmp_path / "document.json"
    document: dict[str, Any] = {"revision": "2026.09.27", "entries": [{"id": "one"}]}
    path.write_text(json.dumps(document), encoding="utf-8")
    assert read_document(path) == document


def test_a_missing_document_names_its_path(tmp_path: Path) -> None:
    missing = tmp_path / "absent.json"
    with pytest.raises(FileNotFoundError) as raised:
        read_document(missing)
    assert str(missing) in str(raised.value)


def test_a_document_that_is_not_an_object_is_refused(tmp_path: Path) -> None:
    path = tmp_path / "list.json"
    path.write_text("[]", encoding="utf-8")
    with pytest.raises(ValueError):
        read_document(path)


def test_a_section_keeps_the_order_the_file_lists() -> None:
    document = {"entries": [{"id": "one"}, {"id": "two"}]}
    assert [entry["id"] for entry in document_section(document, "entries")] == ["one", "two"]


def test_a_section_the_document_does_not_declare_is_empty() -> None:
    assert document_section({}, "entries") == ()
    assert document_section({"entries": None}, "entries") == ()
    assert document_section({"entries": "one"}, "entries") == ()


def test_an_entry_that_is_not_an_object_is_skipped() -> None:
    document = {"entries": [{"id": "one"}, "two", ["three"]]}
    assert document_section(document, "entries") == ({"id": "one"},)
