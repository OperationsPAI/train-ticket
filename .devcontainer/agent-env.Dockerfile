# syntax=docker/dockerfile:1.6

ARG BASE_IMAGE=train-ticket-dev:local
FROM ${BASE_IMAGE}

USER root
COPY --chown=vscode:vscode . /workspace/train-ticket
WORKDIR /workspace/train-ticket

# ARL's seed-workspace init container mounts an EmptyDir at its seed path.
# Keeping root as the default image user lets that init container populate the
# volume before the executor starts.
USER root
