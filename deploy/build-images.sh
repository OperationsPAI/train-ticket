#!/usr/bin/env bash
set -euo pipefail

TAG="${1:-${IMAGE_TAG:-local}}"
KIND_CLUSTER="${KIND_CLUSTER:-kind}"
LOAD_INTO_KIND="${LOAD_INTO_KIND:-1}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The image list is DERIVED from the rendered Helm release rather than written
# out here. It used to be a hand-maintained `services=(...)` array that had to
# be kept in sync with the manifests by hand, and it drifted: four deployed
# services (group-booking, invoicing, loyalty-membership, travel-insurance)
# were missing from it, and the failure mode is a silent ImagePullBackOff on
# an otherwise clean build+deploy. What the cluster actually pulls is the only
# list that can be authoritative.
#
# Rendering rather than grepping the chart templates is not incidental: a
# template contains `{{ include "train-ticket.image" ... }}`, so a grep over the
# template sources matches nothing and the guard below would be all that stands
# between that and a build of zero images.
mapfile -t services < <(
  IMAGE_TAG="$TAG" "${ROOT_DIR}/deploy/render-manifests.sh" \
    | grep -ohE 'image:[[:space:]]*train-ticket/[A-Za-z0-9._-]+:' \
    | sed -E 's|.*train-ticket/([A-Za-z0-9._-]+):.*|\1|' \
    | LC_ALL=C sort -u
)

if [[ "${#services[@]}" -eq 0 ]]; then
  echo "ERROR: derived 0 images from the rendered Helm release." >&2
  echo "       Check that deploy/render-manifests.sh succeeds:" >&2
  echo "         deploy/render-manifests.sh | head" >&2
  exit 1
fi

# A manifest can only be satisfied if the matching Dockerfile exists. Checking
# up front turns "failed 30 builds in" into an immediate, complete report.
missing=()
for service in "${services[@]}"; do
  [[ -f "${ROOT_DIR}/deploy/docker/${service}/Dockerfile" ]] || missing+=("${service}")
done
if [[ "${#missing[@]}" -gt 0 ]]; then
  echo "ERROR: the Helm chart references images with no Dockerfile:" >&2
  for service in "${missing[@]}"; do
    printf '  - %s (expected deploy/docker/%s/Dockerfile)\n' "$service" "$service" >&2
  done
  exit 1
fi

# Loading into a cluster that does not exist wastes a full 39-image build
# before failing on the very last step, so check the name up front. The
# default is 'kind' for backwards compatibility, but this repo's cluster is
# 'train-ticket' -- `make deploy` passes KIND_CLUSTER explicitly.
if [[ "${LOAD_INTO_KIND}" == "1" ]]; then
  if ! command -v kind >/dev/null 2>&1; then
    echo "ERROR: kind is not installed but LOAD_INTO_KIND=1." >&2
    echo "       Set LOAD_INTO_KIND=0 to build images without loading them." >&2
    exit 1
  fi
  if ! kind get clusters 2>/dev/null | grep -qx "${KIND_CLUSTER}"; then
    echo "ERROR: no kind cluster named '${KIND_CLUSTER}'." >&2
    echo "       Available: $(kind get clusters 2>/dev/null | tr '\n' ' ')" >&2
    echo "       Set KIND_CLUSTER=<name>, or LOAD_INTO_KIND=0 to skip loading." >&2
    exit 1
  fi
fi

echo "==> Building ${#services[@]} images derived from the rendered Helm release"

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
The chart references train-ticket/<service>:local via global.imageTag.
If you built a different tag, set global.imageTag to match before installing.
MSG
