from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check_documented_api_routes.py"
SPEC = importlib.util.spec_from_file_location("check_documented_api_routes", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
routes = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(routes)


class DocumentedApiRoutesTest(unittest.TestCase):
    def parse(self, suffix: str, source: str) -> set[tuple[str, str]]:
        with tempfile.NamedTemporaryFile("w", suffix=suffix, encoding="utf-8") as handle:
            handle.write(source)
            handle.flush()
            return routes.routes_in_source(Path(handle.name))

    def test_normalizes_path_parameter_styles(self) -> None:
        self.assertEqual(routes.normalize_route("/a/:id/b"), "/a/{}/b")
        self.assertEqual(routes.normalize_route("/a/{item_id}/b"), "/a/{}/b")

    def test_parses_spring_class_and_method_mappings(self) -> None:
        parsed = self.parse(
            ".java",
            '@RequestMapping("/api/v1")\n@PostMapping(value = "/orders/{id}")',
        )
        self.assertIn(("POST", "/api/v1/orders/{}"), parsed)

    def test_parses_gin_group_routes(self) -> None:
        parsed = self.parse(
            ".go",
            'v1 := router.Group("/api/v1")\nv1.GET("/orders/:id", handler)',
        )
        self.assertIn(("GET", "/api/v1/orders/{}"), parsed)

    def test_parses_axum_fastapi_and_fastify_routes(self) -> None:
        self.assertIn(
            ("POST", "/api/v1/orders/{}"),
            self.parse(".rs", '.route("/api/v1/orders/{id}", post(handler))'),
        )
        self.assertIn(
            ("GET", "/api/v1/orders/{}"),
            self.parse(
                ".py",
                'router = APIRouter(prefix="/api/v1")\n@router.get("/orders/{id}")',
            ),
        )
        self.assertIn(
            ("POST", "/api/v1/orders"),
            self.parse(
                ".ts",
                'app.post<{ Body: Input }>("/api/v1/orders", async () => {})',
            ),
        )


if __name__ == "__main__":
    unittest.main()
