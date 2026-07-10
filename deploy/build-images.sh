#!/usr/bin/env bash
set -euo pipefail

TAG="${1:-${IMAGE_TAG:-local}}"
KIND_CLUSTER="${KIND_CLUSTER:-kind}"
LOAD_INTO_KIND="${LOAD_INTO_KIND:-1}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

services=(
  place-network
  service-plan
  capacity-availability
  fare-pricing
  trip-planning
  offer-management
  journey-order
  identity-verification
  booking-orchestration
  payment
  payment-channel
  provider-integration
  entitlement-ticketing
  seat-assignment
  fulfillment
  post-sales
  notification
  traveler-profile
  risk-compliance
  corporate-travel
  account
  admin-audit
  customer-service
  finance-settlement
  reporting
  supplier-catalog
  legacy-acl
  waitlist
  wallet-promotion
  dispatch
  disruption-recovery
  transfer-management
  ancillary-service
)

for service in "${services[@]}"; do
  image="train-ticket/${service}:${TAG}"
  dockerfile="${ROOT_DIR}/deploy/docker/${service}/Dockerfile"
  echo "==> Building ${image}"
  docker build -f "${dockerfile}" -t "${image}" "${ROOT_DIR}"
  if [[ "${LOAD_INTO_KIND}" == "1" ]]; then
    echo "==> Loading ${image} into kind cluster ${KIND_CLUSTER}"
    kind load docker-image "${image}" --name "${KIND_CLUSTER}"
  fi
done

cat <<MSG
Built ${#services[@]} service images with tag '${TAG}'.
Kubernetes manifests reference train-ticket/<service>:local by default.
If you built a different tag, update deploy/k8s image tags before applying.
MSG
