# syntax=docker/dockerfile:1.7
FROM python:3.12-bookworm AS builder
RUN pip install --no-cache-dir uv
WORKDIR /src
ENV UV_LINK_MODE=copy UV_COMPILE_BYTECODE=1
COPY platform/python-kit ./platform/python-kit
COPY services/identity-verification/pyproject.toml services/identity-verification/uv.lock ./services/identity-verification/
COPY services/identity-verification/src ./services/identity-verification/src
RUN sed -i 's/editable = true/editable = false/' services/identity-verification/pyproject.toml
RUN --mount=type=cache,target=/root/.cache/uv uv venv /opt/venv &&     uv pip install --python /opt/venv/bin/python ./platform/python-kit ./services/identity-verification "uvicorn[standard]"

FROM python:3.12-slim
RUN useradd --system --create-home --home-dir /home/app app
COPY --from=builder /opt/venv /opt/venv
COPY services/identity-verification/migrations /app/migrations
ENV PATH=/opt/venv/bin:$PATH PORT=8080 REDIS_URL=redis://localhost:6379 MIGRATIONS_DIR=/app/migrations
USER app
EXPOSE 8080
CMD ["sh", "-c", "exec uvicorn identity_verification.main:app --host 0.0.0.0 --port ${PORT:-8080} --workers ${WEB_CONCURRENCY:-4}"]
