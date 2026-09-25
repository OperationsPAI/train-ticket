#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
CHART="${ROOT_DIR}/deploy/helm/train-ticket"
VALUES="${CHART}/values.yaml"

command -v helm >/dev/null 2>&1 || {
  echo "hpa-replica-ownership: helm is required" >&2
  exit 2
}

render_install=$(helm template train-ticket "${CHART}" \
  --namespace train-ticket \
  --values "${VALUES}" \
  --show-only templates/services.yaml)
render_upgrade=$(helm template train-ticket "${CHART}" \
  --namespace train-ticket \
  --values "${VALUES}" \
  --is-upgrade \
  --show-only templates/services.yaml)

deployment_body() {
  local name=$1
  awk -v target="${name}" '
    /^---$/ {
      if (capture) {
        capture = 0
        found = 1
      }
      kind = ""
      metadata = 0
      next
    }
    found { next }
    /^kind: Deployment$/ { kind = "Deployment" }
    /^metadata:$/ { metadata = 1; next }
    metadata && /^  name: / {
      if (kind == "Deployment" && $2 == target) capture = 1
      metadata = 0
    }
    capture { print }
  '
}

notification_install=$(printf '%s\n' "${render_install}" | deployment_body notification)
notification_upgrade=$(printf '%s\n' "${render_upgrade}" | deployment_body notification)
account_upgrade=$(printf '%s\n' "${render_upgrade}" | deployment_body account)

printf '%s\n' "${notification_install}" | grep -q '^  replicas: 2$' || {
  echo "hpa-replica-ownership: an install must seed notification at minReplicas=2" >&2
  exit 1
}

if printf '%s\n' "${notification_upgrade}" | grep -q '^  replicas:'; then
  echo "hpa-replica-ownership: an upgrade must leave notification replicas to its HPA" >&2
  exit 1
fi

printf '%s\n' "${account_upgrade}" | grep -q '^  replicas: 1$' || {
  echo "hpa-replica-ownership: a non-autoscaled Deployment must retain Helm-owned replicas" >&2
  exit 1
}

echo "hpa-replica-ownership: OK"
