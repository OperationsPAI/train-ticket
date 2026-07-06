# syntax=docker/dockerfile:1.7
FROM golang:1.26-alpine AS builder
WORKDIR /src
ENV GOWORK=off
RUN apk add --no-cache git ca-certificates
COPY platform/go-kit ./platform/go-kit
COPY platform/go-runtime ./platform/go-runtime
COPY services/service-plan/go.mod services/service-plan/go.sum ./services/service-plan/
RUN --mount=type=cache,target=/go/pkg/mod cd services/service-plan && go mod download
COPY services/service-plan ./services/service-plan
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build     cd services/service-plan && CGO_ENABLED=0 GOOS=linux go build -o /out/service-plan ./cmd/service-plan

FROM alpine:3.22
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /out/service-plan /usr/local/bin/service-plan
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/service-plan"]
