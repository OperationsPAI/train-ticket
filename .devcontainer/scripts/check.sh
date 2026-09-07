#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"

require_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "missing required tool: $1" >&2
    exit 127
  fi
}

for tool in git gh python3 java mvn go node npm pnpm tsc pip3 uv rustc cargo; do
  require_tool "${tool}"
done

test -d docs/02-domains
test -d docs/03-ddd-final
test -f docs/03-ddd-final/implementation-roadmap.md
test -f docs/03-ddd-final/phase-1-contract.md
test -f docs/03-ddd-final/change-routing.md
test -f service-catalog.json
test -f project-index.yaml
test -x scripts/check-skeleton.py

python3 --version
for cmd in java mvn go node npm pnpm tsc pip3 uv rustc cargo gh jq yq helm kubectl skaffold docker; do
  if command -v "${cmd}" >/dev/null 2>&1; then
    case "${cmd}" in
      java)
        if java -version >/tmp/train-ticket-java-version 2>&1; then
          head -n 1 /tmp/train-ticket-java-version
        fi
        ;;
      go) go version || true ;;
      mvn) mvn -version | head -n 1 || true ;;
      kubectl) kubectl version --client=true --output=yaml 2>/dev/null | awk '/gitVersion:/ {print "kubectl " $2; exit}' || true ;;
      docker)
        if docker version >/dev/null 2>&1; then
          docker version --format 'docker {{.Client.Version}} -> {{.Server.Version}}'
        else
          echo "docker client present; daemon unavailable"
        fi
        ;;
      *) "${cmd}" --version 2>/dev/null | head -n 1 || true ;;
    esac
  fi
done

echo "Greenfield DDD workspace smoke check passed."

# ── Package-index reachability ───────────────────────────────────────────────
# Version banners above prove the binaries exist; they say nothing about whether
# the configured package indexes can actually serve a package. A mirror that
# answers 403 for every request breaks `uv run --frozen pytest` in all 8 Python
# services while `uv --version` still looks perfectly healthy, so check
# resolution for real. These are metadata-only queries -- no installs, no
# downloads -- and take roughly a second each.
#
# `setuptools` is the package that matters: it is the build backend of every
# Python service and the only build requirement not pinned in their uv.lock
# files, so it is the one thing a `--frozen` run must fetch from the index.
#
# Set TRAIN_TICKET_SKIP_INDEX_CHECKS=1 to skip when working offline on purpose.

if [ "${TRAIN_TICKET_SKIP_INDEX_CHECKS:-0}" = "1" ]; then
  echo "index checks: skipped (TRAIN_TICKET_SKIP_INDEX_CHECKS=1)"
  exit 0
fi

online() {
  # "Is there egress at all", not "is PyPI up" -- a pypi.org-specific outage
  # must surface as a real uv failure below, not be masked as "offline".
  curl -fsS --max-time 8 -o /dev/null "${NPM_CONFIG_REGISTRY:-https://registry.npmjs.org}/typescript" 2>/dev/null \
    || curl -fsS --max-time 8 -o /dev/null https://pypi.org/simple/setuptools/ 2>/dev/null
}

index_failures=0

check_index() {
  local label="$1"
  shift
  if "$@" >/tmp/train-ticket-index-check 2>&1; then
    echo "index check ok: ${label}"
  else
    echo "index check FAILED: ${label}" >&2
    head -n 12 /tmp/train-ticket-index-check >&2
    index_failures=$((index_failures + 1))
  fi
}

if ! online; then
  echo "index checks: skipped, no network egress detected" >&2
else
  # uv: resolve the build backend every Python service needs.
  # UV_INDEX_URL is the deprecated spelling but still wins if someone exports it,
  # so report whichever is actually in effect.
  printf '%s\n' 'setuptools>=68' > /tmp/train-ticket-index-probe.txt
  check_index "uv -> ${UV_INDEX_URL:-${UV_DEFAULT_INDEX:-https://pypi.org/simple}}" \
    uv pip compile /tmp/train-ticket-index-probe.txt \
      --quiet --no-cache --output-file /tmp/train-ticket-index-probe.out

  # pip: same package, through pip's own index configuration.
  check_index "pip -> ${PIP_INDEX_URL:-https://pypi.org/simple}" \
    pip3 index versions setuptools

  # npm/pnpm: the JS registry is a single point of failure with no fallback
  # mechanism, so confirm it can serve metadata for a package the TS services use.
  if command -v pnpm >/dev/null 2>&1; then
    check_index "pnpm -> $(pnpm config get registry 2>/dev/null || echo unknown)" \
      pnpm view typescript version
  fi
fi

if [ "${index_failures}" -ne 0 ]; then
  echo "" >&2
  echo "${index_failures} package index check(s) failed. The toolchain binaries are" >&2
  echo "installed but at least one configured index cannot serve packages, so" >&2
  echo "builds and test runs will fail on a cold cache. See the index policy" >&2
  echo "comment in .devcontainer/Dockerfile." >&2
  exit 1
fi

echo "Package index checks passed."
