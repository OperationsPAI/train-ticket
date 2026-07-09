# syntax=docker/dockerfile:1.7
FROM golang:1.26-alpine AS builder
WORKDIR /src
ENV GOWORK=off
RUN apk add --no-cache git ca-certificates
COPY platform/go-kit ./platform/go-kit
COPY platform/go-runtime ./platform/go-runtime
COPY services/dispatch/go.mod services/dispatch/go.sum ./services/dispatch/
RUN --mount=type=cache,target=/go/pkg/mod cd services/dispatch && go mod download
COPY services/dispatch ./services/dispatch
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build     cd services/dispatch && CGO_ENABLED=0 GOOS=linux go build -o /out/dispatch ./cmd/dispatch

FROM alpine:3.22
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /out/dispatch /usr/local/bin/dispatch
COPY --from=builder /src/services/dispatch/migrations /app/migrations
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379 MIGRATIONS_DIR=/app/migrations
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/dispatch"]
