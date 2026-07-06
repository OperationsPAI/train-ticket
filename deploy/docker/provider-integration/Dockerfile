# syntax=docker/dockerfile:1.7
FROM golang:1.26-alpine AS builder
WORKDIR /src
ENV GOWORK=off
RUN apk add --no-cache git ca-certificates
COPY platform/go-kit ./platform/go-kit
COPY platform/go-runtime ./platform/go-runtime
COPY services/provider-integration/go.mod services/provider-integration/go.sum ./services/provider-integration/
RUN --mount=type=cache,target=/go/pkg/mod cd services/provider-integration && go mod download
COPY services/provider-integration ./services/provider-integration
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build     cd services/provider-integration && CGO_ENABLED=0 GOOS=linux go build -o /out/provider-integration ./cmd/provider-integration

FROM alpine:3.22
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /out/provider-integration /usr/local/bin/provider-integration
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/provider-integration"]
