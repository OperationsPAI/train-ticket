# syntax=docker/dockerfile:1.7
FROM golang:1.26-bookworm AS builder
WORKDIR /src
COPY platform/go-kit ./platform/go-kit
COPY platform/go-runtime ./platform/go-runtime
COPY services/seat-assignment/go.mod services/seat-assignment/go.sum ./services/seat-assignment/
COPY services/seat-assignment/internal ./services/seat-assignment/internal
COPY services/seat-assignment/cmd ./services/seat-assignment/cmd
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    cd services/seat-assignment && go build -o /usr/local/bin/seat-assignment ./cmd/seat-assignment

FROM debian:bookworm-slim
RUN useradd --system --create-home --home-dir /home/app app && \
    apt-get update && apt-get install -y --no-install-recommends ca-certificates && \
    rm -rf /var/lib/apt/lists/*
COPY --from=builder /usr/local/bin/seat-assignment /usr/local/bin/seat-assignment
COPY services/seat-assignment/migrations /app/migrations
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379 MIGRATIONS_DIR=/app/migrations
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/seat-assignment"]
