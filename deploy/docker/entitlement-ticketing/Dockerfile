# syntax=docker/dockerfile:1.7
FROM rust:1.91-bookworm AS builder
WORKDIR /src
COPY platform/shared-kernel-rust ./platform/shared-kernel-rust
COPY platform/rust-kit ./platform/rust-kit
COPY services/entitlement-ticketing/Cargo.toml services/entitlement-ticketing/Cargo.lock ./services/entitlement-ticketing/
COPY services/entitlement-ticketing/src ./services/entitlement-ticketing/src
RUN --mount=type=cache,target=/usr/local/cargo/registry --mount=type=cache,target=/src/services/entitlement-ticketing/target     cargo build --release --manifest-path services/entitlement-ticketing/Cargo.toml &&     cp services/entitlement-ticketing/target/release/entitlement-ticketing /usr/local/bin/entitlement-ticketing

FROM debian:bookworm-slim
RUN useradd --system --create-home --home-dir /home/app app &&     apt-get update && apt-get install -y --no-install-recommends ca-certificates &&     rm -rf /var/lib/apt/lists/*
COPY --from=builder /usr/local/bin/entitlement-ticketing /usr/local/bin/entitlement-ticketing
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entitlement-ticketing"]
