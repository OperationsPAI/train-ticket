FROM golang:1.26-alpine AS build
WORKDIR /src
COPY platform/go-kit /src/platform/go-kit
COPY platform/go-runtime /src/platform/go-runtime
COPY services/travel-insurance /src/services/travel-insurance
WORKDIR /src/services/travel-insurance
RUN go build -o /out/travel-insurance ./cmd/travel-insurance

FROM alpine:3.22
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /out/travel-insurance /app/travel-insurance
COPY services/travel-insurance/migrations /app/migrations
USER app
EXPOSE 8080
ENTRYPOINT ["/app/travel-insurance"]
