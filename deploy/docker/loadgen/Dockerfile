# syntax=docker/dockerfile:1.7
#
# Load generator (Go). Build context is the REPO ROOT, matching every other
# service here and the `docker build -f <dockerfile> <ROOT_DIR>` invocation in
# deploy/build-images.sh. Paths below are therefore repo-relative.
#
# GOWORK=off: deploy/loadgen-go is a member of the root go.work so that a plain
# `go build` / `go test` works for developers without setting an env var, but
# the image build deliberately resolves the module standalone -- go.work is not
# copied into the context, and workspace mode would fail on the missing member
# modules. The module's own go.mod pins the same dependency versions the
# workspace resolves (go-redis v9.21.0), so both paths build identical code.
FROM golang:1.26-alpine AS builder
WORKDIR /src
ENV GOWORK=off
RUN apk add --no-cache ca-certificates

# Dependency layer: copy only the manifests so `go mod download` is cached
# until the dependency set actually changes.
COPY deploy/loadgen-go/go.mod deploy/loadgen-go/go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download

COPY deploy/loadgen-go/ ./
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /out/loadgen .

FROM alpine:3.22
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /out/loadgen /usr/local/bin/loadgen

# Entity-registry state dir (run.state_file). The Deployment mounts an
# emptyDir over /data; chown here keeps standalone `docker run` working too.
RUN mkdir /data && chown app:app /data
USER app

# The real config is supplied by the `loadgen-config` ConfigMap mounted at
# /etc/loadgen (see deploy/helm/train-ticket/templates/loadgen.yaml, which
# renders that ConfigMap from deploy/helm/train-ticket/loadgen-config.yaml). No
# config is baked into the image: a stale copy that silently shadows the
# ConfigMap is worse than an explicit startup failure.
ENV LOADGEN_CONFIG=/etc/loadgen/config.yaml
ENTRYPOINT ["/usr/local/bin/loadgen"]
