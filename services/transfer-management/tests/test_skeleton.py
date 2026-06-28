import unittest

from transfer_management import create_app, health, profile


class SkeletonTest(unittest.TestCase):
    def test_profile_matches_domain(self) -> None:
        service_profile = profile()
        self.assertEqual(service_profile["service_id"], "transfer-management")
        self.assertEqual(service_profile["domain"], "Transfer Management")
        self.assertEqual(health(), "ok")

    def test_fastapi_health_route_is_registered(self) -> None:
        app = create_app()
        routes = {route.path for route in app.routes}
        self.assertIn("/health", routes)


if __name__ == "__main__":
    unittest.main()
