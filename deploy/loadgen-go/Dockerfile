FROM golang:1.22-alpine AS build

WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /loadgen .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates
COPY --from=build /loadgen /usr/local/bin/loadgen
COPY config.yaml /etc/loadgen/config.yaml

ENV LOADGEN_CONFIG=/etc/loadgen/config.yaml
ENTRYPOINT ["loadgen"]
