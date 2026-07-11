# syntax=docker/dockerfile:1.7
FROM python:3.12-slim
RUN pip install --no-cache-dir httpx redis pyyaml
RUN useradd --system --create-home --home-dir /home/app app
WORKDIR /app
COPY deploy/loadgen/loadgen.py /app/loadgen.py
COPY deploy/loadgen/config.yaml /app/config.yaml
RUN mkdir /data && chown app /data
USER app
ENV LOADGEN_CONFIG=/app/config.yaml
CMD ["python", "/app/loadgen.py"]
