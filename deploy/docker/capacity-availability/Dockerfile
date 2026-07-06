# syntax=docker/dockerfile:1.7
FROM rust:1.91-bookworm AS builder
WORKDIR /src
COPY platform/shared-kernel-rust ./platform/shared-kernel-rust
COPY platform/rust-kit ./platform/rust-kit
COPY services/capacity-availability/Cargo.toml services/capacity-availability/Cargo.lock ./services/capacity-availability/
COPY services/capacity-availability/src ./services/capacity-availability/src
RUN --mount=type=cache,target=/usr/local/cargo/registry --mount=type=cache,target=/src/services/capacity-availability/target     cargo build --release --manifest-path services/capacity-availability/Cargo.toml --features redis-impl &&     cp services/capacity-availability/target/release/capacity-availability /usr/local/bin/capacity-availability

FROM debian:bookworm-slim
RUN useradd --system --create-home --home-dir /home/app app &&     apt-get update && apt-get install -y --no-install-recommends ca-certificates &&     rm -rf /var/lib/apt/lists/*
COPY --from=builder /usr/local/bin/capacity-availability /usr/local/bin/capacity-availability
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/capacity-availability"]
