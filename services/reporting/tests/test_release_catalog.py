import json
import tempfile
import unittest
from pathlib import Path

from reporting.application.catalog import catalog_file, load_release_catalog


class ReleaseCatalogTest(unittest.TestCase):
    def test_the_catalog_ships_with_the_package(self) -> None:
        self.assertTrue(catalog_file().is_file(), catalog_file())

    def test_the_catalog_parses_and_declares_both_sections(self) -> None:
        document = json.loads(catalog_file().read_text(encoding="utf-8"))
        self.assertEqual(document["schemaVersion"], "trainticket.release-catalog/v1")
        catalog = load_release_catalog()
        self.assertEqual(len(catalog.metrics), len(document["metrics"]))
        self.assertEqual(len(catalog.dashboards), len(document["dashboards"]))

    def test_every_entry_is_addressable_in_the_store(self) -> None:
        catalog = load_release_catalog()
        for entry in catalog.metrics:
            self.assertTrue(str(entry["metricId"]).strip())
        for entry in catalog.dashboards:
            self.assertTrue(str(entry["dashboardId"]).strip())

    def test_the_catalog_ids_are_unique(self) -> None:
        catalog = load_release_catalog()
        metric_ids = [str(entry["metricId"]) for entry in catalog.metrics]
        dashboard_ids = [str(entry["dashboardId"]) for entry in catalog.dashboards]
        self.assertEqual(len(metric_ids), len(set(metric_ids)))
        self.assertEqual(len(dashboard_ids), len(set(dashboard_ids)))

    def test_entries_are_returned_as_the_file_wrote_them(self) -> None:
        document = json.loads(catalog_file().read_text(encoding="utf-8"))
        catalog = load_release_catalog()
        self.assertEqual(list(catalog.metrics[0]), list(document["metrics"][0]))

    def test_a_catalog_that_is_absent_is_an_error(self) -> None:
        with self.assertRaises(FileNotFoundError):
            load_release_catalog(Path("no-such-catalog.json"))


    def test_every_dashboard_entry_states_a_state(self) -> None:
        for entry in load_release_catalog().dashboards:
            self.assertTrue(str(entry["status"]).strip())


    def test_every_dashboard_entry_carries_the_id_and_the_name(self) -> None:
        for entry in load_release_catalog().dashboards:
            self.assertTrue(str(entry["dashboardId"]).strip())
            self.assertTrue(str(entry["name"]).strip())


    def test_a_section_the_document_omits_seeds_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            path.write_text(json.dumps({"schemaVersion": "trainticket.release-catalog/v1"}), encoding="utf-8")
            catalog = load_release_catalog(path)
        self.assertEqual(catalog.metrics, ())
        self.assertEqual(catalog.dashboards, ())


if __name__ == "__main__":
    unittest.main()
