# syntax=docker/dockerfile:1.7
FROM golang:1.26-alpine AS builder
WORKDIR /src
ENV GOWORK=off
RUN apk add --no-cache git ca-certificates
COPY platform/go-kit ./platform/go-kit
COPY platform/go-runtime ./platform/go-runtime
COPY services/payment-channel/go.mod services/payment-channel/go.sum ./services/payment-channel/
RUN --mount=type=cache,target=/go/pkg/mod cd services/payment-channel && go mod download
COPY services/payment-channel ./services/payment-channel
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build     cd services/payment-channel && CGO_ENABLED=0 GOOS=linux go build -o /out/payment-channel ./cmd/payment-channel

FROM alpine:3.22
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /out/payment-channel /usr/local/bin/payment-channel
COPY --from=builder /src/services/payment-channel/migrations /app/migrations
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379 MIGRATIONS_DIR=/app/migrations
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/payment-channel"]
