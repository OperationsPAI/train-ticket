import unittest

from legacy_acl.runtime import health, profile


class LegacyAclSkeletonTest(unittest.TestCase):
    def test_service_profile(self) -> None:
        self.assertEqual("ok", health())
        self.assertEqual("legacy-acl", profile()["service_id"])


if __name__ == "__main__":
    unittest.main()
