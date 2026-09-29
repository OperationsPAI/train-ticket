from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from fare_pricing import settings


class ResolutionRegisterTest(unittest.TestCase):
    """The register a deployment installs is read from its own document."""

    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        patched = mock.patch.dict(os.environ, {settings.CONFIG_DIR_ENV: str(self.directory)})
        patched.start()
        self.addCleanup(patched.stop)

    def write(self, served: list[str]) -> None:
        (self.directory / settings.RESOLUTION_DOCUMENT).write_text(
            json.dumps({"resolution": {"servedContracts": served}}), encoding="utf-8"
        )

    def test_the_document_states_the_contracts_this_deployment_serves(self) -> None:
        self.write(["contract-managed", "contract-default"])
        self.assertEqual(
            settings.served_contracts(), frozenset({"contract-managed", "contract-default"})
        )

    def test_a_process_with_no_document_serves_every_contract_it_holds(self) -> None:
        self.assertIsNone(settings.served_contracts())

    def test_the_environment_overlays_the_document(self) -> None:
        self.write(["contract-managed", "contract-default"])
        with mock.patch.dict(os.environ, {settings.CONTRACTS_ENV: "contract-managed"}):
            self.assertEqual(settings.served_contracts(), frozenset({"contract-managed"}))
