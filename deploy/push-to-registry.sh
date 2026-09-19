set -euo pipefail

# Retag every service image the rendered release names, and push it.
#
# k3s has no `kind load` and the nodes accept no key this host holds, so the
# images reach the cluster through a registry both can read. Only the service
# images go: the third-party ones are pulled from their own registries, which
# `global.infraRegistry=` (explicitly empty) is what preserves.
#
# The list is DERIVED from the rendered release rather than written out, for the
# same reason deploy/build-images.sh derives it: a hand-kept list drifts, and the
# failure mode is a silent ImagePullBackOff on an otherwise clean deploy.

REGISTRY="${REGISTRY:-mirrors.tencent.com}"
ORG="${ORG:-microservice}"
TAG="${TAG:-local}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

mapfile -t services < <(
  helm template tt "${ROOT_DIR}/deploy/helm/train-ticket" \
      -f "${ROOT_DIR}/deploy/helm/values-cluster.yaml" \
      --set "global.imageRegistry=${REGISTRY}" \
      --set "global.imageOrg=${ORG}" \
      --set global.infraRegistry= \
    | grep -ohE "image: ${REGISTRY}/${ORG}/[A-Za-z0-9._-]+:" \
    | sed -E "s|.*/([A-Za-z0-9._-]+):.*|\1|" \
    | LC_ALL=C sort -u
)

if [[ "${#services[@]}" -eq 0 ]]; then
  echo "ERROR: derived 0 service images from the rendered release." >&2
  exit 1
fi

missing=()
for service in "${services[@]}"; do
  docker image inspect "train-ticket/${service}:${TAG}" >/dev/null 2>&1 \
    || missing+=("${service}")
done
if [[ "${#missing[@]}" -gt 0 ]]; then
  echo "ERROR: not built locally: ${missing[*]}" >&2
  echo "       run: deploy/build-images.sh ${TAG}" >&2
  exit 1
fi

echo "==> pushing ${#services[@]} service image(s) to ${REGISTRY}/${ORG}"
for service in "${services[@]}"; do
  local_ref="train-ticket/${service}:${TAG}"
  remote_ref="${REGISTRY}/${ORG}/${service}:${TAG}"
  docker tag "${local_ref}" "${remote_ref}"
  docker push "${remote_ref}"
done

echo
echo "Pushed ${#services[@]} image(s). Deploy with:"
echo "  helm upgrade --install train-ticket deploy/helm/train-ticket \\"
echo "    -f deploy/helm/values-k3s.yaml --namespace train-ticket --create-namespace --wait"
