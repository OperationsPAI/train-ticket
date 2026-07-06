# syntax=docker/dockerfile:1.7
FROM ghcr.io/astral-sh/uv:0.9-python3.12-bookworm-slim AS builder
WORKDIR /src
ENV UV_LINK_MODE=copy UV_COMPILE_BYTECODE=1
COPY platform/python-kit ./platform/python-kit
COPY services/legacy-acl/pyproject.toml ./services/legacy-acl/
COPY services/legacy-acl/src ./services/legacy-acl/src
RUN sed -i 's/editable = true/editable = false/' services/legacy-acl/pyproject.toml
RUN --mount=type=cache,target=/root/.cache/uv uv venv /opt/venv &&     uv pip install --python /opt/venv/bin/python ./platform/python-kit ./services/legacy-acl "uvicorn[standard]"

FROM python:3.12-slim
RUN useradd --system --create-home --home-dir /home/app app
COPY --from=builder /opt/venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH PORT=8080 REDIS_URL=redis://localhost:6379
USER app
EXPOSE 8080
CMD ["sh", "-c", "exec uvicorn legacy_acl.main:app --host 0.0.0.0 --port ${PORT:-8080}"]
