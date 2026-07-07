# syntax=docker/dockerfile:1.7
FROM ghcr.io/astral-sh/uv:0.9-python3.12-bookworm-slim AS builder
WORKDIR /src
ENV UV_LINK_MODE=copy UV_COMPILE_BYTECODE=1
COPY platform/python-kit ./platform/python-kit
COPY services/risk-compliance/pyproject.toml services/risk-compliance/uv.lock ./services/risk-compliance/
COPY services/risk-compliance/src ./services/risk-compliance/src
RUN sed -i 's/editable = true/editable = false/' services/risk-compliance/pyproject.toml
RUN --mount=type=cache,target=/root/.cache/uv uv venv /opt/venv &&     uv pip install --python /opt/venv/bin/python ./platform/python-kit ./services/risk-compliance "uvicorn[standard]"

FROM python:3.12-slim
RUN useradd --system --create-home --home-dir /home/app app
COPY --from=builder /opt/venv /opt/venv
COPY services/risk-compliance/migrations /app/migrations
ENV PATH=/opt/venv/bin:$PATH PORT=8080 REDIS_URL=redis://localhost:6379 MIGRATIONS_DIR=/app/migrations
USER app
EXPOSE 8080
CMD ["sh", "-c", "exec uvicorn risk_compliance.api:create_app --factory --host 0.0.0.0 --port ${PORT:-8080}"]
