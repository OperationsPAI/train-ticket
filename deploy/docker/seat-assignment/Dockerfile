# syntax=docker/dockerfile:1.7
FROM rust:1.91-bookworm AS builder
WORKDIR /src
COPY platform/shared-kernel-rust ./platform/shared-kernel-rust
COPY platform/rust-kit ./platform/rust-kit
COPY services/seat-assignment/Cargo.toml services/seat-assignment/Cargo.lock ./services/seat-assignment/
COPY services/seat-assignment/src ./services/seat-assignment/src
COPY services/seat-assignment/migrations ./services/seat-assignment/migrations
RUN --mount=type=cache,target=/usr/local/cargo/registry --mount=type=cache,target=/src/services/seat-assignment/target \
    cargo build --release --manifest-path services/seat-assignment/Cargo.toml && \
    cp services/seat-assignment/target/release/seat-assignment /usr/local/bin/seat-assignment

FROM debian:bookworm-slim
RUN useradd --system --create-home --home-dir /home/app app && \
    apt-get update && apt-get install -y --no-install-recommends ca-certificates && \
    rm -rf /var/lib/apt/lists/*
COPY --from=builder /usr/local/bin/seat-assignment /usr/local/bin/seat-assignment
COPY --from=builder /src/services/seat-assignment/migrations /app/migrations
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379 MIGRATIONS_DIR=/app/migrations
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/seat-assignment"]
