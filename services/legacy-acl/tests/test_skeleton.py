import unittest

from legacy_acl.runtime import health, profile


class LegacyAclSkeletonTest(unittest.TestCase):
    def test_service_profile(self) -> None:
        self.assertEqual("ok", health())
        self.assertEqual("legacy-acl", profile()["service_id"])


if __name__ == "__main__":
    unittest.main()


def test_downstream_failure_prefers_domain_code() -> None:
    from legacy_acl.downstream import _downstream_failure

    message, code = _downstream_failure(
        "offer-management",
        '{"code": "DOMAIN_RULE_VIOLATION", "message": "No consumed Fare Pricing quote matches", "details": {"domainCode": "MISSING_FARE_QUOTE"}}',
        "Unprocessable Entity",
    )
    assert message == "No consumed Fare Pricing quote matches"
    assert code == "MISSING_FARE_QUOTE"

    _, generic = _downstream_failure("payment", '{"code": "NOT_FOUND", "message": "missing"}', "Not Found")
    assert generic == "NOT_FOUND"
