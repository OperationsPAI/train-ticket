from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "contract_lint.py"
SPEC = importlib.util.spec_from_file_location("contract_lint", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
contract_lint = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contract_lint)


class ContractLintTest(unittest.TestCase):
    def test_money_units_requires_currency_context(self) -> None:
        capacity = [
            'type Capacity struct {',
            '    Units []SeatUnit `json:"units"`',
            '}',
        ]
        money = [
            'const amount = {',
            '  "currency": "CNY",',
            '  "units": 100',
            '};',
        ]
        self.assertEqual(contract_lint.money_key_violations(capacity), [])
        self.assertEqual(contract_lint.money_key_violations(money), [3])

    def test_cents_is_always_a_money_violation(self) -> None:
        self.assertEqual(contract_lint.money_key_violations(['{"cents": 10}']), [1])

    def test_test_and_messaging_boundary_detection(self) -> None:
        self.assertTrue(contract_lint.is_test(Path("services/a/tests/example.rs")))
        self.assertTrue(contract_lint.is_test(Path("services/a/example_test.go")))
        self.assertTrue(
            contract_lint.is_messaging_boundary(
                Path("services/a/src/infrastructure/messaging/Subscriber.java")
            )
        )
        self.assertTrue(
            contract_lint.is_messaging_boundary(Path("services/a/src/subscriber.ts"))
        )
        self.assertFalse(
            contract_lint.is_messaging_boundary(Path("services/a/src/domain.ts"))
        )

    def test_stream_rule_only_matches_string_literals(self) -> None:
        self.assertIsNone(
            contract_lint.STREAM_LITERAL_RE.search(
                "// observed lag on events:journey-order"
            )
        )
        self.assertIsNotNone(
            contract_lint.STREAM_LITERAL_RE.search(
                'const stream = "events:journey-order";'
            )
        )

    def test_source_command_rule_matches_keys_but_not_negative_assertions(self) -> None:
        self.assertIsNotNone(
            contract_lint.SOURCE_COMMAND_KEY_RE.search(
                'const envelope = { "sourceCommandId": commandId };'
            )
        )
        self.assertIsNotNone(
            contract_lint.SOURCE_COMMAND_KEY_RE.search(
                "payload = {'sourceCommandId': command_id}"
            )
        )
        self.assertIsNotNone(
            contract_lint.SOURCE_COMMAND_KEY_RE.search(
                'SourceCommandID string `json:"sourceCommandId,omitempty"`'
            )
        )
        self.assertIsNone(
            contract_lint.SOURCE_COMMAND_KEY_RE.search(
                'assertFalse(serialized.containsKey("sourceCommandId"));'
            )
        )


if __name__ == "__main__":
    unittest.main()
