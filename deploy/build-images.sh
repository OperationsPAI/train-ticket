#!/usr/bin/env bash
set -euo pipefail

TAG="${1:-${IMAGE_TAG:-local}}"
KIND_CLUSTER="${KIND_CLUSTER:-kind}"
LOAD_INTO_KIND="${LOAD_INTO_KIND:-1}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Each entry must satisfy BOTH:
#   - deploy/docker/<entry>/Dockerfile exists, and
#   - a deploy/k8s manifest references image train-ticket/<entry>:local
#     (services.yaml for the business services, loadgen.yaml for loadgen).
# Keep this array in sync with the train-ticket/* images in those manifests.
# NOTE: trip-planning is served by the Rust crate services/trip-planning-rs and
# the manifest requires train-ticket/trip-planning-rs:local, so the entry is
# 'trip-planning-rs' (built from deploy/docker/trip-planning-rs/Dockerfile).
# deploy/docker/trip-planning/Dockerfile is a byte-identical leftover of the
# same Rust build and is intentionally NOT built here; it should be deleted.
services=(
  place-network
  service-plan
  capacity-availability
  fare-pricing
  trip-planning-rs
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
  marketing-campaign
  dispatch
  disruption-recovery
  transfer-management
  ancillary-service
  group-booking
  invoicing
  loyalty-membership
  travel-insurance
  # Resident load generator (Go, deploy/loadgen-go). Referenced by
  # deploy/k8s/loadgen.yaml as train-ticket/loadgen:local. Previously built
  # out-of-band by deploy/loadgen/run.sh, which was removed with the Python
  # implementation, so it is built here like every other image.
  loadgen
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
