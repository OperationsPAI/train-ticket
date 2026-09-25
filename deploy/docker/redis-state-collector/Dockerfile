# syntax=docker/dockerfile:1.7
FROM golang:1.26-alpine AS builder
WORKDIR /src
ENV GOWORK=off
RUN apk add --no-cache git ca-certificates
COPY platform/go-runtime ./platform/go-runtime
COPY tools/redis-state-collector/go.mod tools/redis-state-collector/go.sum ./tools/redis-state-collector/
RUN --mount=type=cache,target=/go/pkg/mod cd tools/redis-state-collector && go mod download
COPY tools/redis-state-collector ./tools/redis-state-collector
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    cd tools/redis-state-collector && CGO_ENABLED=0 GOOS=linux go build -o /out/redis-state-collector ./cmd/redis-state-collector

FROM alpine:3.22
RUN apk add --no-cache ca-certificates && addgroup -S app && adduser -S app -G app
COPY --from=builder /out/redis-state-collector /usr/local/bin/redis-state-collector
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/redis-state-collector"]
