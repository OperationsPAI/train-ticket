# syntax=docker/dockerfile:1.7
FROM golang:1.26-alpine AS builder
WORKDIR /src
ENV GOWORK=off
RUN apk add --no-cache git ca-certificates
COPY platform/go-kit ./platform/go-kit
COPY platform/go-runtime ./platform/go-runtime
COPY services/fulfillment/go.mod services/fulfillment/go.sum ./services/fulfillment/
RUN --mount=type=cache,target=/go/pkg/mod cd services/fulfillment && go mod download
COPY services/fulfillment ./services/fulfillment
# The estate's boarding roster is the ticketing authority's statement, and this
# image carries it so a boarding fact is measured against that statement rather
# than against a list compiled in here. Vendored at build, which is also what
# keeps the two sides from drifting: the roster is a build input of this image.
COPY services/entitlement-ticketing/config ./services/entitlement-ticketing/config
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build     cd services/fulfillment && CGO_ENABLED=0 GOOS=linux go build -o /out/fulfillment ./cmd/fulfillment

FROM alpine:3.22
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /out/fulfillment /usr/local/bin/fulfillment
COPY --from=builder /src/services/fulfillment/migrations /app/migrations
COPY --from=builder --chmod=0644 /src/services/entitlement-ticketing/config/boarding-readers.txt /app/config/boarding-readers.txt
# The roster is read by the unprivileged app user, and the directory a copy
# creates for it is not traversable on its own.
RUN chmod 0755 /app/config
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379 MIGRATIONS_DIR=/app/migrations FULFILLMENT_ESTATE_DIR=/app/config
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/fulfillment"]
