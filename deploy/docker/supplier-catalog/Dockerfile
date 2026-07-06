# syntax=docker/dockerfile:1.7
FROM golang:1.26-alpine AS builder
WORKDIR /src
ENV GOWORK=off
RUN apk add --no-cache git ca-certificates
COPY platform/go-kit ./platform/go-kit
COPY platform/go-runtime ./platform/go-runtime
COPY services/supplier-catalog/go.mod services/supplier-catalog/go.sum ./services/supplier-catalog/
RUN --mount=type=cache,target=/go/pkg/mod cd services/supplier-catalog && go mod download
COPY services/supplier-catalog ./services/supplier-catalog
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build     cd services/supplier-catalog && CGO_ENABLED=0 GOOS=linux go build -o /out/supplier-catalog ./cmd/supplier-catalog

FROM alpine:3.22
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /out/supplier-catalog /usr/local/bin/supplier-catalog
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/supplier-catalog"]
