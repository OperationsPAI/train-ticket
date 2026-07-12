# syntax=docker/dockerfile:1.7
FROM rust:1.91-bookworm AS builder
WORKDIR /src
COPY platform/shared-kernel-rust ./platform/shared-kernel-rust
COPY platform/rust-kit ./platform/rust-kit
COPY services/trip-planning-rs/Cargo.toml services/trip-planning-rs/Cargo.lock ./services/trip-planning-rs/
COPY services/trip-planning-rs/src ./services/trip-planning-rs/src
COPY services/trip-planning-rs/migrations ./services/trip-planning-rs/migrations
RUN --mount=type=cache,target=/usr/local/cargo/registry --mount=type=cache,target=/src/services/trip-planning-rs/target \
    cargo build --release --manifest-path services/trip-planning-rs/Cargo.toml --features redis-impl && \
    cp services/trip-planning-rs/target/release/trip-planning /usr/local/bin/trip-planning

FROM debian:bookworm-slim
RUN useradd --system --create-home --home-dir /home/app app && \
    apt-get update && apt-get install -y --no-install-recommends ca-certificates && \
    rm -rf /var/lib/apt/lists/*
COPY --from=builder /usr/local/bin/trip-planning /usr/local/bin/trip-planning
COPY --from=builder /src/services/trip-planning-rs/migrations /app/migrations
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379 MIGRATIONS_DIR=/app/migrations
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/trip-planning"]
