#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"

MODE="${1:-package}"
CHART_DIR="${CHART_DIR:-manifests/helm/trainticket}"
HELM_RELEASE="${HELM_RELEASE:-ts}"
HELM_NAMESPACE="${HELM_NAMESPACE:-ts}"
MAVEN_THREADS="${MAVEN_THREADS:-1.5C}"
MAVEN_SKIP_TESTS="${MAVEN_SKIP_TESTS:-true}"

require_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "missing required tool: $1" >&2
    exit 127
  fi
}

verify_tools() {
  for tool in git java mvn node npm python3 pip3 helm kubectl skaffold yq jq; do
    require_tool "${tool}"
  done

  java -version
  mvn -version | head -n 1
  node --version
  npm --version
  python3 --version
  helm version --short
  skaffold version
  kubectl version --client=true --output=yaml | awk '/gitVersion:/ {print "kubectl " $2; exit}'
}

helm_validate() {
  require_tool helm
  require_tool yq
  test -f "${CHART_DIR}/Chart.yaml"

  local chart_tmp chart_copy repo_added
  chart_tmp="$(mktemp -d)"
  chart_copy="${chart_tmp}/chart"
  cp -a "${CHART_DIR}" "${chart_copy}"

  repo_added=false
  while IFS=$'\t' read -r name repo; do
    if [ -n "${name}" ] && [ -n "${repo}" ]; then
      helm repo add "${name}" "${repo}" --force-update >/dev/null
      repo_added=true
    fi
  done < <(yq -r '.dependencies[]? | select(.repository | test("^https?://")) | [.name, .repository] | @tsv' "${chart_copy}/Chart.yaml")

  if [ "${repo_added}" = true ]; then
    helm repo update >/dev/null
  fi

  helm dependency build "${chart_copy}"
  helm lint "${chart_copy}"
  helm template "${HELM_RELEASE}" "${chart_copy}" --namespace "${HELM_NAMESPACE}" >/tmp/train-ticket-helm-template.yaml
  rm -rf "${chart_tmp}"
}

maven_package() {
  require_tool mvn
  mvn -B -T "${MAVEN_THREADS}" clean package -Dmaven.test.skip="${MAVEN_SKIP_TESTS}"
}

skaffold_images() {
  require_tool skaffold
  require_tool docker
  if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon is not reachable. Mount /var/run/docker.sock or configure a compatible builder." >&2
    if [ -S /var/run/docker.sock ]; then
      sock_gid="$(stat -c '%g' /var/run/docker.sock)"
      echo "The mounted Docker socket gid is ${sock_gid}. For direct docker run, add: --group-add ${sock_gid}" >&2
      echo "Alternatively run the image as root for image-build jobs, or use the Dev Container post-create hook." >&2
    fi
    exit 1
  fi
  skaffold build --push=false
}

case "${MODE}" in
  smoke)
    verify_tools
    helm_validate
    ;;
  package)
    verify_tools
    maven_package
    helm_validate
    ;;
  images)
    skaffold_images
    ;;
  all)
    verify_tools
    maven_package
    helm_validate
    skaffold_images
    ;;
  *)
    echo "usage: $0 [smoke|package|images|all]" >&2
    exit 2
    ;;
esac
