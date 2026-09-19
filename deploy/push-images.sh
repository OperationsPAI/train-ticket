#!/usr/bin/env bash
set -euo pipefail
#
# Push every image the release references into the registry it names.
#
# WHY THIS EXISTS SEPARATELY FROM build-images.sh
# ----------------------------------------------
# build-images.sh ends in `kind load docker-image`, which is the only way images
# reach a kind node: kind nodes share this host's Docker daemon, so there is no
# registry involved and no push to write. A real cluster has no such shortcut.
# `admin:school` in particular cannot be fed any other way -- its own pull
# registry (10.10.10.240) sits on an internal subnet that is unroutable from
# here, and the nodes do not see this host's Docker daemon. So images have to go
# through a registry both sides can reach, and this is the step that puts them
# there.
#
# WHAT IT PUSHES, AND HOW IT DECIDES
# ----------------------------------
# The lists are DERIVED, not maintained, for the same reason build-images.sh
# derives its list: a hand-written one drifts, and the failure mode is a silent
# ImagePullBackOff on an otherwise clean run.
#
# The CANONICAL render is the source of truth for *what images exist*. It is
# the same chart rendered under the LOCAL naming -- no registry, no shared
# repository, `imageOrg` as build-images.sh tags them -- so it names both kinds
# of image the way they come from upstream:
#
#   - services, as `train-ticket/<name>:<tag>` -- the naming build-images.sh
#     keys on. Grepping the target render for the same thing would match nothing
#     once images collapse into a single repository, and the guard below would
#     be the only thing between that and a green run that pushed nothing.
#   - infra images, as their upstream references (`postgres:16-alpine`,
#     `jaegertracing/all-in-one:1.57`) -- i.e. every other image in the render.
#
# That naming is a convention, not a deployment, so it is three `--set`s rather
# than a values file. It used to be a second profile that happened to spell it,
# and joining an inventory to whichever file happens to say `train-ticket/` is
# how the two drift apart.
#
# The TARGET render (HELM_VALUES) is the source of truth for *what each image's
# full reference is*, which is the whole point: the registry, the repository
# layout and the tag format are decided by the chart, not by this script. It is
# the same bytes helm will install.
#
# The two are joined on identity -- service name for services, and
# `<basename>-<tag>` for infra -- so neither list has to know about the other's
# naming, and neither has to know the registry.
#
# INFRA IMAGES ARE PUSHED TOO, WHEN THE CHART MIRRORS THEM
# `train-ticket.infraImage` mirrors the upstream images into the service
# repository when `global.imageRepo` is set. That is not a convenience: a
# cluster that can reach a registry but not `auth.docker.io` cannot pull
# `jaegertracing/all-in-one` or `axllent/mailpit` at all, and `postgres`/
# `redis` only appear to work because their layers happen to be cached on the
# nodes already. Mirroring them makes the release depend on one reachable
# registry. When the chart is NOT mirroring (infraRegistry: ""), each infra
# target equals its source, and this script skips them rather than pushing a
# pointless duplicate.
#
# Usage:
#   deploy/push-images.sh
#   IMAGE_TAG=20260913 LOCAL_TAG=local \
#     HELM_VALUES="deploy/helm/values-prod.yaml deploy/helm/values-acr.yaml" \
#     deploy/push-images.sh
#   DRY_RUN=1 ... deploy/push-images.sh     # resolve and print, push nothing
#
# LOCAL_TAG is the tag the service images were BUILT with (the Makefile's
# IMAGE_TAG on the build side). IMAGE_TAG is the tag the chart will DEPLOY. They
# default to the same value and only diverge when pushing an existing build
# under a new deployed tag.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

LOCAL_TAG="${LOCAL_TAG:-${IMAGE_TAG:-local}}"
IMAGE_TAG="${IMAGE_TAG:-$LOCAL_TAG}"
HELM_VALUES="${HELM_VALUES:-${ROOT_DIR}/deploy/helm/values-cluster.yaml}"
# The canonical render names the images the way they exist LOCALLY, which is
# how build-images.sh tags them. That is a naming convention, not a profile, so
# it is set here rather than read from a values file -- there used to be a
# second profile that happened to carry it, and joining an inventory to
# whichever file happened to spell `train-ticket/` is how the two drift.
CANONICAL_ORG="${CANONICAL_ORG:-train-ticket}"
NAMESPACE="${NAMESPACE:-train-ticket}"
DRY_RUN="${DRY_RUN:-0}"

command -v docker >/dev/null 2>&1 || { echo "push-images: docker is not installed" >&2; exit 2; }

render() {
  HELM_VALUES="$HELM_VALUES" IMAGE_TAG="$IMAGE_TAG" HELM_SET="${1:-}" \
    "${ROOT_DIR}/deploy/render-manifests.sh"
}

# --- 1. The canonical inventory: what images exist --------------------------
CANONICAL_RENDER="$(mktemp)"
TARGET_RENDER="$(mktemp)"
trap 'rm -f "$CANONICAL_RENDER" "$TARGET_RENDER"' EXIT

render "global.imageRegistry= global.imageOrg=${CANONICAL_ORG} global.imageRepo=" \
  >"$CANONICAL_RENDER"
render >"$TARGET_RENDER"

# Every image reference in a rendered manifest, anchored to `image:` lines.
#
# The anchor is not cosmetic. A bare grep for the repository path also matches
# the DATABASE_URL DSN (`postgresql://trainticket:trainticket-dev@postgres-core:5432/…`),
# which contains the credentials username and would otherwise be parsed as an
# image called `trainticket-dev@postgres-core`.
image_refs() {
  grep -E '^ *image: ' "$1" | grep -oE '[^ ]+:[^ ]+' | LC_ALL=C sort -u
}

mapfile -t services < <(
  image_refs "$CANONICAL_RENDER" \
    | grep -E "^${CANONICAL_ORG}/" \
    | sed -E "s|^${CANONICAL_ORG}/([A-Za-z0-9._-]+):.*|\1|" \
    | LC_ALL=C sort -u
)

if [[ "${#services[@]}" -eq 0 ]]; then
  echo "ERROR: derived 0 services from the canonical render." >&2
  echo "       Check that deploy/render-manifests.sh succeeds under the local naming:" >&2
  echo "         HELM_SET='global.imageRegistry= global.imageOrg=${CANONICAL_ORG} global.imageRepo=' \\" >&2
  echo "           deploy/render-manifests.sh | head" >&2
  exit 1
fi

mapfile -t infra_src < <(
  image_refs "$CANONICAL_RENDER" | grep -vE '^train-ticket/' || true
)

# --- 2. Map each to its target reference ------------------------------------
TARGET_REFS="$(image_refs "$TARGET_RENDER")"

declare -A target_of
while IFS= read -r ref; do
  # Two service layouts, distinguished by the trailing part of the reference:
  #
  #   per-service  <registry>/<org>/<service>:<tag>        -> ends "/<svc>:<tag>"
  #   single-repo  <registry>/<org>/<repo>:<service>-<tag> -> ends ":<svc>-<tag>"
  #
  # Matching against the known service list is what keeps this from having to
  # know which layout is in play, or what the repository is called.
  for svc in "${services[@]}"; do
    case "$ref" in
      *"/${svc}:${IMAGE_TAG}"|*":${svc}-${IMAGE_TAG}") target_of["$svc"]="$ref" ;;
    esac
  done
done <<<"$TARGET_REFS"

missing=()
for svc in "${services[@]}"; do
  [[ -n "${target_of[$svc]:-}" ]] || missing+=("$svc")
done
if [[ "${#missing[@]}" -gt 0 ]]; then
  echo "ERROR: the target profile renders no image for these services:" >&2
  printf '  - %s\n' "${missing[@]}" >&2
  echo "       HELM_VALUES=${HELM_VALUES}" >&2
  exit 1
fi

# Infra targets, matched by the `<basename>-<tag>` the chart gives them when it
# mirrors. A ref that matches no infra source is a service ref and is skipped
# here; a source with no target means the chart is not mirroring it.
declare -A infra_target_of
while IFS= read -r ref; do
  ref_tag="${ref##*:}"
  for src in "${infra_src[@]}"; do
    src_repo="${src%:*}"; src_tag="${src##*:}"
    case "$ref_tag" in
      "$(basename "$src_repo")-${src_tag}") infra_target_of["$src"]="$ref" ;;
    esac
  done
done <<<"$TARGET_REFS"

# --- 3. Tag and push --------------------------------------------------------
mirrored=()
for src in "${infra_src[@]}"; do
  dst="${infra_target_of[$src]:-}"
  # Same reference on both sides means the chart is not mirroring (an explicit
  # `infraRegistry: ""`, or a profile that still resolves to docker.io). Pushing
  # it would be a no-op at best and a needless public duplicate at worst.
  [[ -n "$dst" && "$dst" != "$src" ]] && mirrored+=("$src -> $dst")
done

total=$(( ${#services[@]} + ${#mirrored[@]} ))
if [[ "$DRY_RUN" == "1" ]]; then
  echo "==> DRY_RUN: would push ${total} images (${#services[@]} services, ${#mirrored[@]} infra)"
else
  echo "==> Pushing ${total} images (${#services[@]} services, ${#mirrored[@]} infra)"
fi

failed=()
push_one() {
  local src="$1" dst="$2"

  if ! docker image inspect "$src" >/dev/null 2>&1; then
    echo "ERROR: no local image ${src}" >&2
    echo "       Services are built by deploy/build-images.sh ${LOCAL_TAG}." >&2
    echo "       Infra images are upstream: docker pull ${src}" >&2
    exit 1
  fi

  if [[ "$DRY_RUN" == "1" ]]; then
    echo "    ${src}  ->  ${dst}"
    return 0
  fi

  echo "==> ${dst}"
  docker tag "$src" "$dst"
  docker push "$dst" || failed+=("$dst")
}

for svc in "${services[@]}"; do
  push_one "train-ticket/${svc}:${LOCAL_TAG}" "${target_of[$svc]}"
done

for src in "${infra_src[@]}"; do
  dst="${infra_target_of[$src]:-}"
  [[ -n "$dst" && "$dst" != "$src" ]] || continue
  push_one "$src" "$dst"
done

if [[ "$DRY_RUN" == "1" ]]; then
  echo "DRY_RUN: nothing was tagged or pushed."
  exit 0
fi

if [[ "${#failed[@]}" -gt 0 ]]; then
  echo >&2
  echo "ERROR: ${#failed[@]} of ${total} pushes failed:" >&2
  printf '  - %s\n' "${failed[@]}" >&2
  exit 1
fi

cat <<MSG
Pushed ${total} images (${#services[@]} services, ${#mirrored[@]} infra).
The release references them via global.imageRegistry/Org/Repo in HELM_VALUES:
  ${HELM_VALUES}
Install with the same values and global.imageTag=${IMAGE_TAG}.
MSG
