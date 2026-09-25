#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ALLOWED_LANGUAGES = {"java", "golang", "python", "rust", "typescript"}


def fail(message: str) -> None:
    raise SystemExit(f"skeleton check failed: {message}")


def command_ok(command: list[str]) -> bool:
    try:
        subprocess.run(command, cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        return True
    except (OSError, subprocess.CalledProcessError):
        return False


def require_tool(name: str, strict: bool) -> bool:
    if shutil.which(name):
        return True
    if strict:
        fail(f"required tool is missing in strict mode: {name}")
    return False


def run(command: list[str], cwd: Path, env: dict[str, str] | None = None) -> None:
    print(f"+ ({cwd.relative_to(ROOT)}) {' '.join(command)}")
    subprocess.run(command, cwd=cwd, env=env, check=True)


def npm_install_command(service_root: Path) -> list[str]:
    if (service_root / "package-lock.json").exists():
        return ["npm", "ci"]
    return ["npm", "install"]


def python_test_args(service_root: Path) -> list[str]:
    pyproject = (service_root / "pyproject.toml").read_text(encoding="utf-8")
    if re.search(r"(?m)^\s*[\"']?pytest[\"']?\s*(?:[=<>~!]|$)", pyproject):
        return ["-m", "pytest", "-q"]
    return ["-m", "unittest", "discover", "-s", "tests"]


def java_major_version() -> int | None:
    try:
        result = subprocess.run(
            ["java", "-version"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    match = re.search(r'version "([0-9]+)', result.stderr + result.stdout)
    return int(match.group(1)) if match else None


def required_java_major(services: list[dict]) -> int:
    required = 0
    for service in services:
        if service["language"] != "java":
            continue
        pom = (ROOT / service["path"] / "pom.xml").read_text(encoding="utf-8")
        match = re.search(r"<java\.version>([0-9]+)</java\.version>", pom)
        if match:
            required = max(required, int(match.group(1)))
    return required


def require(path: Path) -> None:
    if not path.exists():
        fail(f"missing required path: {path.relative_to(ROOT)}")


def entries(catalog: dict) -> list[dict]:
    return list(catalog.get("platformModules", [])) + list(catalog.get("services", []))


def validate_catalog(catalog: dict) -> list[dict]:
    if catalog.get("schemaVersion") != "trainticket.service-catalog/v1":
        fail("unexpected service-catalog schemaVersion")
    services = entries(catalog)
    seen: set[str] = set()
    for service in services:
        service_id = service["id"]
        if service_id in seen:
            fail(f"duplicate service id: {service_id}")
        seen.add(service_id)
        language = service["language"]
        if language not in ALLOWED_LANGUAGES:
            fail(f"unsupported language for {service_id}: {language}")
        root = ROOT / service["path"]
        require(root / "README.md")
        for doc in service.get("docs", []):
            require(ROOT / doc)
        if language == "golang":
            require(root / "go.mod")
            if service["path"].startswith("services/"):
                profile_paths = (
                    root / "internal/domain/profile.go",
                    root / "domain/profile.go",
                )
                if not any(path.is_file() for path in profile_paths):
                    fail(
                        f"missing Go service profile under {root.relative_to(ROOT)}: "
                        "expected internal/domain/profile.go or domain/profile.go"
                    )
        elif language == "java":
            require(root / "pom.xml")
            require(root / "src/main")
        elif language == "python":
            require(root / "pyproject.toml")
            require(root / "src")
            require(root / "tests")
        elif language == "rust":
            require(root / "Cargo.toml")
            require(root / "src/lib.rs")
        elif language == "typescript":
            require(root / "package.json")
            require(root / "tsconfig.json")
            require(root / "src/index.ts")
    return services


def java_contract_check_class(service_root: Path) -> str:
    candidates = list(service_root.glob("src/test/java/**/ApplicationContractCheck.java"))
    if len(candidates) != 1:
        fail(f"expected one Java ApplicationContractCheck under {service_root.relative_to(ROOT)}")
    package_name = ""
    for line in candidates[0].read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("package ") and line.endswith(";"):
            package_name = line.removeprefix("package ").removesuffix(";")
            break
    if not package_name:
        fail(f"missing package declaration in {candidates[0].relative_to(ROOT)}")
    return f"{package_name}.ApplicationContractCheck"


def run_available_language_checks(services: list[dict], strict: bool) -> None:
    if require_tool("go", strict):
        for service in services:
            if service["language"] == "golang":
                service_root = ROOT / service["path"]
                if strict:
                    run(["go", "mod", "tidy"], service_root)
                run(["go", "test", "./..."], service_root)
    else:
        print("skip go checks: go not found")

    for service in services:
        if service["language"] == "python":
            service_root = ROOT / service["path"]
            test_args = python_test_args(service_root)
            if strict:
                require_tool("uv", True)
                run(["uv", "sync"], service_root)
                run(["uv", "run", "python", *test_args], service_root)
            elif (service_root / ".venv/bin/python").is_file():
                run([str(service_root / ".venv/bin/python"), *test_args], service_root)
            else:
                env = os.environ.copy()
                env["PYTHONPATH"] = os.pathsep.join(
                    [
                        str(service_root / "src"),
                        str(ROOT / "platform/python-kit/src"),
                    ]
                )
                run([sys.executable, *test_args], service_root, env=env)

    if require_tool("cargo", strict):
        for service in services:
            if service["language"] == "rust":
                run(["cargo", "test", "--quiet"], ROOT / service["path"])
    else:
        print("skip rust checks: cargo not found")

    has_java = require_tool("mvn", strict) and require_tool("java", strict)
    installed_java = java_major_version() if has_java else None
    minimum_java = required_java_major(services)
    if has_java and installed_java is not None and installed_java >= minimum_java:
        for service in services:
            if service["language"] == "java":
                service_root = ROOT / service["path"]
                run(["mvn", "-q", "test"], service_root)
                run(["java", "-cp", "target/classes:target/test-classes", java_contract_check_class(service_root)], service_root)
    elif strict and has_java:
        fail(f"Java {minimum_java} is required, found {installed_java or 'unknown'}")
    else:
        print(
            "skip java checks: "
            f"Java {minimum_java} is required, found {installed_java or 'unavailable'}"
        )

    for service in services:
        if service["language"] != "typescript":
            continue
        service_root = ROOT / service["path"]
        if not require_tool("npm", strict):
            print(f"skip typescript build for {service['id']}: npm not available")
            continue
        if strict or not (service_root / "node_modules/.bin/tsc").exists():
            run(npm_install_command(service_root), service_root)
        run(["npm", "test"], service_root)


def main() -> None:
    strict = "--strict" in sys.argv[1:]
    require(ROOT / "project-index.yaml")
    require(ROOT / "docs/05-service-architecture/language-selection.md")
    catalog = json.loads((ROOT / "service-catalog.json").read_text(encoding="utf-8"))
    services = validate_catalog(catalog)
    run_available_language_checks(services, strict)
    print(f"skeleton check passed: {len(services)} modules")


if __name__ == "__main__":
    main()
