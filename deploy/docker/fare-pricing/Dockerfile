# syntax=docker/dockerfile:1.7
FROM ghcr.io/astral-sh/uv:0.9-python3.12-bookworm-slim AS builder
WORKDIR /src
ENV UV_LINK_MODE=copy UV_COMPILE_BYTECODE=1
COPY platform/python-kit ./platform/python-kit
COPY services/fare-pricing/pyproject.toml services/fare-pricing/uv.lock ./services/fare-pricing/
COPY services/fare-pricing/src ./services/fare-pricing/src
RUN --mount=type=cache,target=/root/.cache/uv uv venv /opt/venv &&     uv pip install --python /opt/venv/bin/python ./platform/python-kit ./services/fare-pricing "uvicorn[standard]"

FROM python:3.12-slim
RUN useradd --system --create-home --home-dir /home/app app
COPY --from=builder /opt/venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH PORT=8080 REDIS_URL=redis://localhost:6379
USER app
EXPOSE 8080
CMD ["sh", "-c", "exec uvicorn fare_pricing.main:app --host 0.0.0.0 --port ${PORT:-8080}"]
