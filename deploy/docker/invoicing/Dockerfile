# syntax=docker/dockerfile:1.7
FROM rust:1.91-bookworm AS builder
WORKDIR /src
COPY platform/shared-kernel-rust ./platform/shared-kernel-rust
COPY platform/rust-kit ./platform/rust-kit
COPY services/invoicing/Cargo.toml services/invoicing/Cargo.lock ./services/invoicing/
COPY services/invoicing/src ./services/invoicing/src
COPY services/invoicing/migrations ./services/invoicing/migrations
RUN --mount=type=cache,target=/usr/local/cargo/registry --mount=type=cache,target=/src/services/invoicing/target \
    cargo build --release --manifest-path services/invoicing/Cargo.toml && \
    cp services/invoicing/target/release/invoicing /usr/local/bin/invoicing

FROM debian:bookworm-slim
RUN useradd --system --create-home --home-dir /home/app app && \
    apt-get update && apt-get install -y --no-install-recommends ca-certificates && \
    rm -rf /var/lib/apt/lists/*
COPY --from=builder /usr/local/bin/invoicing /usr/local/bin/invoicing
COPY --from=builder /src/services/invoicing/migrations /app/migrations
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379 MIGRATIONS_DIR=/app/migrations
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/invoicing"]
