#!/usr/bin/env bash
#
# Render the Helm release to stdout, for scripts that need to derive a list from
# what will actually be deployed.
#
# WHY EVERY DERIVED LIST GOES THROUGH THIS
# ----------------------------------------
# build-images.sh and smoke.sh both used to grep the retired kustomize
# manifests. That was the right instinct -- a hand-maintained list drifts, and it
# did: four deployed services were missing from build-images.sh's array, which
# produces a silent ImagePullBackOff on an otherwise clean build. But kustomize
# is gone, and grepping the chart's TEMPLATES instead would be worse than
# either: templates contain `{{ include "train-ticket.image" ... }}`, not image
# names, so a grep would match nothing and the "refusing to pass vacuously"
# guards are the only thing standing between that and a green run that checked
# nothing.
#
# The rendered release is the single authoritative list, and it is authoritative
# for a stronger reason than the manifests were: it is the exact bytes helm will
# install, so a values or templating change cannot make the derived list and the
# deployed reality disagree.
#
# Callers must still guard against an empty result. A render can fail (bad
# values, a `fail` in a template) and this script exits non-zero then, but under
# `mapfile < <(...)` a non-zero exit is invisible to the caller.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE="${HELM_RELEASE:-train-ticket}"
CHART="${HELM_CHART:-${ROOT_DIR}/deploy/helm/train-ticket}"
VALUES="${HELM_VALUES:-${ROOT_DIR}/deploy/helm/values-cluster.yaml}"
NS="${NAMESPACE:-train-ticket}"

command -v helm >/dev/null 2>&1 || {
  echo "render-manifests: helm is not installed (needed to derive the service list)" >&2
  exit 2
}

# IMAGE_TAG mirrors the --set the Makefile passes to `helm upgrade`. Without it,
# `make IMAGE_TAG=foo deploy` would render :local here while installing :foo, so
# build-images.sh would build the wrong tag -- the same silent
# ImagePullBackOff the --set was added to prevent, just moved one step earlier.
# Unset means "whatever the values files say", which is what a bare
# `deploy/render-manifests.sh` should show.
#
# HELM_VALUES is a whitespace-separated LIST, matching helm's own `-f` layering
# order (later files win). An environment profile that only differs from
# another by a handful of keys -- values-acr.yaml over values-prod.yaml, say --
# should not have to be a full copy of the file it is a delta against, and a
# single-file restriction here would force exactly that, because every derived
# list in this directory (build-images.sh, smoke.sh, push-images.sh) renders
# through here. Splitting on whitespace is what makes `helm -f a -f b`
# expressible as one variable.
set -- "$RELEASE" "$CHART" --namespace "$NS"
for values_file in $VALUES; do
  set -- "$@" -f "$values_file"
done
if [ -n "${IMAGE_TAG:-}" ]; then
  set -- "$@" --set "global.imageTag=${IMAGE_TAG}"
fi
# HELM_SET is a whitespace-separated list of key=value, for a caller that needs
# one value changed rather than a whole profile. push-images.sh renders the
# image inventory under the LOCAL naming that way.
for pair in ${HELM_SET:-}; do
  set -- "$@" --set "$pair"
done

exec helm template "$@"
