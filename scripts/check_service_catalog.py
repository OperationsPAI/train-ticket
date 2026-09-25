#!/usr/bin/env python3
"""Check that business source, catalog and image build manifests stay aligned."""

from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD_MANIFESTS = {
    "java": "pom.xml",
    "golang": "go.mod",
    "python": "pyproject.toml",
    "rust": "Cargo.toml",
    "typescript": "package.json",
}
RUNTIMES = {
    "java": "jvm",
    "golang": "go",
    "python": "python",
    "rust": "rust",
    "typescript": "node",
}
NON_BUSINESS_IMAGES = {"loadgen"}


def service_source_directories() -> set[str]:
    return {
        path.name
        for path in (ROOT / "services").iterdir()
        if path.is_dir()
        and any((path / manifest).is_file() for manifest in BUILD_MANIFESTS.values())
    }


def docker_image_ids() -> set[str]:
    return {
        dockerfile.parent.name
        for dockerfile in (ROOT / "deploy" / "docker").glob("*/Dockerfile")
    }


def go_workspace_services() -> set[str]:
    text = (ROOT / "go.work").read_text(encoding="utf-8")
    return set(re.findall(r"^\s*\./services/([a-z0-9-]+)\s*$", text, re.MULTILINE))


def validate() -> list[str]:
    catalog = json.loads((ROOT / "service-catalog.json").read_text(encoding="utf-8"))
    services = catalog.get("services", [])
    errors: list[str] = []
    ids: set[str] = set()
    paths: set[str] = set()
    expected_go_workspace: set[str] = set()

    for service in services:
        service_id = service.get("id")
        path_text = service.get("path")
        language = service.get("language")
        if not isinstance(service_id, str) or not service_id:
            errors.append("catalog service has no id")
            continue
        if service_id in ids:
            errors.append(f"duplicate catalog service id: {service_id}")
        ids.add(service_id)
        if not isinstance(path_text, str) or not path_text.startswith("services/"):
            errors.append(f"{service_id}: path must be a direct services/ path")
            continue
        source_name = Path(path_text).name
        if path_text in paths:
            errors.append(f"duplicate catalog service path: {path_text}")
        paths.add(path_text)
        if language not in BUILD_MANIFESTS:
            errors.append(f"{service_id}: unsupported language {language!r}")
            continue
        if service.get("runtime") != RUNTIMES[language]:
            errors.append(
                f"{service_id}: runtime must be {RUNTIMES[language]!r} for {language}"
            )
        service_root = ROOT / path_text
        manifest = service_root / BUILD_MANIFESTS[language]
        if not manifest.is_file():
            errors.append(f"{service_id}: missing build manifest {manifest.relative_to(ROOT)}")
        dockerfile = ROOT / "deploy" / "docker" / service_id / "Dockerfile"
        if not dockerfile.is_file():
            errors.append(f"{service_id}: missing image manifest {dockerfile.relative_to(ROOT)}")
        if language == "golang":
            expected_go_workspace.add(source_name)

    source_names = service_source_directories()
    catalog_names = {Path(path).name for path in paths}
    for name in sorted(source_names - catalog_names):
        errors.append(f"source service missing from catalog: services/{name}")
    for name in sorted(catalog_names - source_names):
        errors.append(f"catalog path has no business source directory: services/{name}")

    image_ids = docker_image_ids()
    for service_id in sorted(ids - image_ids):
        errors.append(f"catalog service missing Dockerfile: deploy/docker/{service_id}/Dockerfile")
    for image_id in sorted(image_ids - ids - NON_BUSINESS_IMAGES):
        errors.append(f"business Dockerfile missing from catalog: deploy/docker/{image_id}/Dockerfile")

    workspace_services = go_workspace_services()
    for name in sorted(expected_go_workspace - workspace_services):
        errors.append(f"Go service missing from go.work: services/{name}")
    for name in sorted(workspace_services - expected_go_workspace):
        errors.append(f"go.work service is not a catalog Go service: services/{name}")

    if len(services) != 37:
        errors.append(f"expected 37 business services, found {len(services)}")
    return errors


def main() -> int:
    errors = validate()
    if errors:
        print(f"service-catalog: {len(errors)} error(s)")
        for error in errors:
            print(f"  - {error}")
        return 1
    print("service-catalog: OK (37 business services, build manifests aligned)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
