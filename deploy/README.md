# Local kind integration environment

This directory contains the Docker and Kubernetes assets for running the 22
integration-ready Train Ticket services plus one Redis Streams broker in a local
kind cluster for 联调.

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
Dockerfiles under `deploy/docker/`.

## Deploy

```bash
kubectl apply -k deploy/k8s
```

The kustomization creates namespace `train-ticket`, a single non-persistent
`redis:7-alpine` Deployment/Service, and one Deployment/Service per integration-ready
service. Service pods receive `REDIS_URL=redis://redis.train-ticket.svc.cluster.local:6379`.

## Smoke check

List all pods and wait until every Deployment is available:

```bash
kubectl -n train-ticket get pods
kubectl -n train-ticket wait --for=condition=Available deployment --all --timeout=180s
kubectl -n train-ticket get pods \
  -o custom-columns='NAME:.metadata.name,READY:.status.containerStatuses[*].ready,PHASE:.status.phase'
```

A rendered-manifest sanity check should report 23 Deployments (22 services + Redis):

```bash
kubectl kustomize deploy/k8s > /tmp/k8s-out.yaml
grep -c 'kind: Deployment' /tmp/k8s-out.yaml
```
