# syntax=docker/dockerfile:1.7
FROM python:3.12-bookworm AS builder
RUN pip install --no-cache-dir uv
WORKDIR /src
ENV UV_LINK_MODE=copy UV_COMPILE_BYTECODE=1
COPY platform/python-kit ./platform/python-kit
COPY services/transfer-management/pyproject.toml services/transfer-management/uv.lock ./services/transfer-management/
COPY services/transfer-management/src ./services/transfer-management/src
RUN sed -i 's/editable = true/editable = false/' services/transfer-management/pyproject.toml
RUN --mount=type=cache,target=/root/.cache/uv uv venv /opt/venv &&     uv pip install --python /opt/venv/bin/python ./platform/python-kit ./services/transfer-management "uvicorn[standard]"

FROM python:3.12-slim
RUN useradd --system --create-home --home-dir /home/app app
COPY --from=builder /opt/venv /opt/venv
COPY services/transfer-management/migrations /app/migrations
ENV PATH=/opt/venv/bin:$PATH PORT=8080 REDIS_URL=redis://localhost:6379 MIGRATIONS_DIR=/app/migrations
USER app
EXPOSE 8080
CMD ["sh", "-c", "exec uvicorn transfer_management.main:app --host 0.0.0.0 --port ${PORT:-8080}"]
