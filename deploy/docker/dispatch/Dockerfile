# syntax=docker/dockerfile:1.7
FROM golang:1.26-bookworm AS builder
WORKDIR /src
COPY go.work go.work.sum ./
COPY platform/go-runtime ./platform/go-runtime
COPY platform/go-kit ./platform/go-kit
COPY services/dispatch/go.mod services/dispatch/go.sum ./services/dispatch/
COPY services/dispatch ./services/dispatch
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go build -C services/dispatch -o /usr/local/bin/dispatch ./cmd/dispatch

FROM debian:bookworm-slim
RUN useradd --system --create-home --home-dir /home/app app && \
    apt-get update && apt-get install -y --no-install-recommends ca-certificates && \
    rm -rf /var/lib/apt/lists/*
COPY --from=builder /usr/local/bin/dispatch /usr/local/bin/dispatch
COPY --from=builder /src/services/dispatch/migrations /app/migrations
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379 MIGRATIONS_DIR=/app/migrations
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/dispatch"]
