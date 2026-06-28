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

for tool in git python3; do
  require_tool "${tool}"
done

test -d docs/02-domains
test -d docs/03-ddd-final
test -f docs/03-ddd-final/implementation-roadmap.md
test -f docs/03-ddd-final/phase-1-contract.md
test -f docs/03-ddd-final/change-routing.md

python3 --version
for cmd in java mvn node npm pip3 uv jq yq helm kubectl skaffold docker; do
  if command -v "${cmd}" >/dev/null 2>&1; then
    case "${cmd}" in
      java)
        if java -version >/tmp/train-ticket-java-version 2>&1; then
          head -n 1 /tmp/train-ticket-java-version
        fi
        ;;
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
