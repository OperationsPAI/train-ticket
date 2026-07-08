# Local kind integration environment

This directory contains the Docker and Kubernetes assets for running the 23
integration-ready Train Ticket services plus Redis Streams and PostgreSQL in a
local kind cluster for 联调.

## Prerequisites

- Docker
- kind
- kubectl with kustomize support (`kubectl kustomize`)

## Build and load images

Build every service image with a common tag and load it into the local kind
cluster:

```bash
# Manifests default to the `local` tag.
deploy/build-images.sh local

# Optional: select a non-default kind cluster name.
KIND_CLUSTER=train-ticket deploy/build-images.sh local

# Optional: build without loading into kind.
LOAD_INTO_KIND=0 deploy/build-images.sh local
```

The script builds images as `train-ticket/<service>:<tag>` using the per-service
Dockerfiles under `deploy/docker/`. It covers the 23 deployed business services;
the six future-scope skeleton services are not part of the deployed set. The
loadgen image is built by `deploy/loadgen/run.sh` from
`deploy/docker/loadgen/Dockerfile`. PostgreSQL uses the stock
`postgres:16-alpine` image and is not built or loaded by `build-images.sh`.

## Deploy

```bash
kubectl apply -k deploy/k8s
```

The kustomization creates namespace `train-ticket`, a Redis `redis:7-alpine`
Deployment/Service with append-only persistence enabled, a stock
`postgres:16-alpine` Deployment/Service/PVC, and one Deployment/Service per
integration-ready service. Service pods receive
`REDIS_URL=redis://redis.train-ticket.svc.cluster.local:6379` and a
service-specific
`DATABASE_URL=postgresql://trainticket:trainticket-dev@postgres:5432/<db>`.

PostgreSQL is sourced directly from the public `postgres:16-alpine` image and is
not included in `build-images.sh`. In kind environments, either allow the node to
pull the public image or preload it into the cluster before deploying:

```bash
docker pull postgres:16-alpine
kind load docker-image postgres:16-alpine --name arl-test
```

## E2E suite

`deploy/e2e/` contains 12 rerunnable scripts with 159+ assertions. Scripts
`01-seed.sh` through `11-legacy-acl.sh` cover the functional flows;
`12-restart.sh` is the whole-cluster restart certification that verifies
PostgreSQL aggregate snapshot rows survive workload restarts and then runs a
post-restart smoke flow.

Run scripts from the repository root against a ready cluster, for example:

```bash
deploy/e2e/01-seed.sh
deploy/e2e/02-purchase.sh
deploy/e2e/12-restart.sh
```

## Resident load generator

`deploy/loadgen/` contains the resident load generator. Use
`deploy/loadgen/run.sh` to build, kind-load, apply, and follow stats, or apply
`deploy/loadgen/loadgen.yaml` after loading `train-ticket/loadgen:local`. The
restart certification script pauses this deployment before comparing snapshot
row counts and resumes it afterward.

## Smoke check

List all pods and wait until every Deployment is available:

```bash
kubectl -n train-ticket get pods
kubectl -n train-ticket wait --for=condition=Available deployment --all --timeout=180s
kubectl -n train-ticket get pods \
  -o custom-columns='NAME:.metadata.name,READY:.status.containerStatuses[*].ready,PHASE:.status.phase'
```

A rendered-manifest sanity check should report 25 Deployments (23 services +
Redis + PostgreSQL):

```bash
kubectl kustomize deploy/k8s > /tmp/k8s-out.yaml
grep -c 'kind: Deployment' /tmp/k8s-out.yaml
```
