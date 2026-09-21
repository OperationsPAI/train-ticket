from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KIT = ROOT / "platform" / "ts-kit"
KIT_NAME = "@trainticket/ts-kit"

# Every TypeScript service's lock must carry the dependencies platform/ts-kit
# declares, because the image builds with `npm ci`, which reproduces the lock
# exactly and resolves nothing. A package ts-kit imports and the lock omits is
# absent at runtime, and the service dies on ERR_MODULE_NOT_FOUND as soon as it
# loads ts-kit's dist.
#
# Measured: ts-kit's observability module added instrumentation-runtime-node,
# sdk-metrics and exporter-metrics-otlp-grpc, all seven locks omitted all
# three, every local build and typecheck passed, and all seven pods
# crash-looped.
#
# Compared as file contents rather than by running npm. `npm ci --dry-run`
# reproduces the lock, so it agrees with itself and cannot see the omission.
# `npm ls` reports the install-links copy as invalid whatever the lock says.
# The question is whether the lock's recorded dependency list for ts-kit
# matches ts-kit's own manifest, and both are JSON on disk.


def kit_dependencies() -> dict[str, str]:
    manifest = json.loads((KIT / "package.json").read_text(encoding="utf-8"))
    return manifest["dependencies"]


def locked_dependencies(lock: Path) -> dict[str, str]:
    """What this lock records as ts-kit's dependencies.

    Keyed by the package path npm writes for a file dependency, which is the
    name under node_modules rather than the relative path in package.json.
    """
    packages = json.loads(lock.read_text(encoding="utf-8"))["packages"]
    entry = packages.get(f"node_modules/{KIT_NAME}")
    if entry is None:
        raise SystemExit(f"{lock.relative_to(ROOT)} has no {KIT_NAME} entry")
    return entry.get("dependencies", {})


def resolved(lock: Path) -> set[str]:
    """Every package this lock installs, by name."""
    packages = json.loads(lock.read_text(encoding="utf-8"))["packages"]
    return {path.rsplit("node_modules/", 1)[-1] for path in packages if path}


def main() -> int:
    wanted = kit_dependencies()
    services = sorted(
        path.parent
        for path in (ROOT / "services").glob("*/package.json")
        if KIT_NAME in json.loads(path.read_text(encoding="utf-8")).get("dependencies", {})
    )
    if not services:
        raise SystemExit(f"no service depends on {KIT_NAME}")

    failed: list[str] = []
    for service in services:
        lock = service / "package-lock.json"
        if not lock.is_file():
            raise SystemExit(f"{service.relative_to(ROOT)} has no package-lock.json")
        recorded = locked_dependencies(lock)
        installed = resolved(lock)
        # Both halves matter. A dependency ts-kit declares and the lock does
        # not record means the lock predates the manifest; one that is recorded
        # but whose package is absent from the tree means the lock records the
        # requirement without satisfying it. The second is what actually
        # crashes the pod.
        stale = sorted(name for name in wanted if name not in recorded)
        missing = sorted(name for name in wanted if name not in installed)
        name = str(service.relative_to(ROOT))
        if stale or missing:
            failed.append(name)
            print(f"{name:<34} FAILED")
            for one in stale:
                print(f"  not recorded as a {KIT_NAME} dependency: {one}")
            for one in missing:
                print(f"  recorded but not installed by the lock: {one}")
        else:
            print(f"{name:<34} OK")

    if failed:
        print(
            f"\n{len(failed)} lock(s) do not carry what {KIT_NAME} declares. "
            f"Regenerate each with `rm -rf node_modules package-lock.json && "
            f"npm install --install-links`: install-links copies ts-kit into "
            f"node_modules, and npm reads that copy's manifest, so removing the "
            f"lock alone leaves the stale dependency list in place.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
