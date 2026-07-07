#!/usr/bin/env bash
# Build, load into kind, (re)apply config and deployment, follow stats.
set -euo pipefail
cd "$(dirname "$0")/../.."
CTX="${KUBE_CONTEXT:-kind-arl-test}"
NS=train-ticket
KIND_NAME="${KIND_NAME:-arl-test}"

docker build -q -f deploy/docker/loadgen/Dockerfile -t train-ticket/loadgen:local .
# plain `kind load` breaks with the containerd-snapshotter digest bug; import directly
docker save train-ticket/loadgen:local \
  | docker exec -i "${KIND_NAME}-control-plane" ctr --namespace=k8s.io images import --snapshotter=overlayfs - >/dev/null

kubectl --context "$CTX" -n $NS create configmap loadgen-config \
  --from-file=config.yaml=deploy/loadgen/config.yaml \
  --dry-run=client -o yaml | kubectl --context "$CTX" -n $NS apply -f -
kubectl --context "$CTX" -n $NS apply -f deploy/loadgen/loadgen.yaml
kubectl --context "$CTX" -n $NS rollout restart deploy/loadgen
kubectl --context "$CTX" -n $NS rollout status deploy/loadgen --timeout=120s
echo "--- following stats (Ctrl-C detaches; loadgen keeps running) ---"
kubectl --context "$CTX" -n $NS logs -f deploy/loadgen | grep --line-buffered -E "\[stats\]|\[final\]|\[bootstrap\]|failed|crashed"
