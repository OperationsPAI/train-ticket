# syntax=docker/dockerfile:1.7
FROM rust:1.91-bookworm AS builder
WORKDIR /src
COPY platform/shared-kernel-rust ./platform/shared-kernel-rust
COPY platform/rust-kit ./platform/rust-kit
COPY services/waitlist/Cargo.toml services/waitlist/Cargo.lock ./services/waitlist/
COPY services/waitlist/src ./services/waitlist/src
COPY services/waitlist/migrations ./services/waitlist/migrations
RUN --mount=type=cache,target=/usr/local/cargo/registry --mount=type=cache,target=/src/services/waitlist/target \
    cargo build --release --manifest-path services/waitlist/Cargo.toml && \
    cp services/waitlist/target/release/waitlist /usr/local/bin/waitlist

FROM debian:bookworm-slim
RUN useradd --system --create-home --home-dir /home/app app && \
    apt-get update && apt-get install -y --no-install-recommends ca-certificates && \
    rm -rf /var/lib/apt/lists/*
COPY --from=builder /usr/local/bin/waitlist /usr/local/bin/waitlist
COPY --from=builder /src/services/waitlist/migrations /app/migrations
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379 MIGRATIONS_DIR=/app/migrations
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/waitlist"]
