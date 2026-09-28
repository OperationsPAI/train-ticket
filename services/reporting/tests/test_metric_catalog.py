import json
import unittest
from pathlib import Path

from reporting.application.catalog import catalog_file, catalog_revision, load_metric_catalog


class MetricCatalogTest(unittest.TestCase):
    def test_the_catalog_parses(self) -> None:
        document = json.loads(catalog_file().read_text(encoding="utf-8"))
        self.assertEqual(document["schemaVersion"], "trainticket.metric-catalog/v1")
        self.assertEqual(len(load_metric_catalog()), len(document["metrics"]))

    def test_the_revision_is_stated_and_matches_the_file(self) -> None:
        document = json.loads(catalog_file().read_text(encoding="utf-8"))
        self.assertEqual(catalog_revision(), document["revision"])
        self.assertTrue(catalog_revision())

    def test_every_entry_carries_the_fields_the_store_reads(self) -> None:
        fields = {
            "metricId",
            "name",
            "description",
            "owner",
            "category",
            "granularity",
            "version",
            "expression",
            "status",
            "publishedAt",
            "sourceLineage",
        }
        for entry in load_metric_catalog():
            self.assertTrue(fields.issubset(entry), sorted(fields - set(entry)))

    def test_the_catalog_keeps_the_order_it_lists(self) -> None:
        document = json.loads(catalog_file().read_text(encoding="utf-8"))
        self.assertEqual(
            [str(entry["metricId"]) for entry in load_metric_catalog()],
            [str(entry["metricId"]) for entry in document["metrics"]],
        )

    def test_every_entry_is_addressable_in_the_store(self) -> None:
        for entry in load_metric_catalog():
            self.assertTrue(str(entry["metricId"]).strip())
            self.assertTrue(str(entry["name"]).strip())
            self.assertTrue(str(entry["category"]).strip())

    def test_the_refund_rate_ships_with_the_operational_definitions(self) -> None:
        entries = {str(entry["metricId"]): entry for entry in load_metric_catalog()}
        self.assertIn("metric-ops-refund-rate", entries)
        self.assertEqual(entries["metric-ops-refund-rate"]["category"], "operational")

    def test_the_catalog_ids_are_unique(self) -> None:
        ids = [str(entry["metricId"]) for entry in load_metric_catalog()]
        self.assertEqual(len(ids), len(set(ids)))

    def test_an_entry_without_an_id_seeds_nothing(self) -> None:
        self.assertEqual(load_metric_catalog(Path("no-such-catalog.json")), ())

    def test_a_missing_catalog_states_no_revision(self) -> None:
        self.assertEqual(catalog_revision(Path("no-such-catalog.json")), "")


if __name__ == "__main__":
    unittest.main()
