# Train Ticket Greenfield Dev Container

This image is a neutral development and AgentM/ARL worker environment for the
greenfield DDD rebuild. It intentionally does not assume the old Maven reactor,
Helm chart, or Skaffold project still exists.

Included tools:

- Temurin JDK 25 and Maven, for Java/Spring Boot service skeletons.
- Go 1.26.x, for Gin adapter, ingestion, and high-throughput service skeletons.
- Node 26 and TypeScript 6, for Fastify service skeletons.
- Python 3 and uv, for scripting or Python services.
- rustup stable and Cargo, for Axum invariant-heavy service skeletons.
- Docker CLI, kubectl, Helm, Skaffold, yq, jq, git-lfs, and rsync.

## Build

```bash
docker build -f .devcontainer/Dockerfile -t train-ticket-dev:local .
```

The container workdir is `/workspace/train-ticket`, owned by the non-root
`vscode` user so ARL can clone or upload a repository snapshot into it.

## Smoke Check

```bash
.devcontainer/scripts/check.sh
```

The check verifies the base toolchain, the DDD document layout, and the
polyglot skeleton catalog.
