# Train Ticket Greenfield Dev Container

This image is a neutral development and AgentM/ARL worker environment for the
greenfield DDD rebuild. It intentionally does not assume the old Maven reactor,
Helm chart, or Skaffold project still exists.

Included tools:

- Temurin JDK 25 and Maven, for Java/Spring Boot service skeletons.
- Go 1.26.x, for Gin adapter, ingestion, and high-throughput service skeletons.
- Node 26 and TypeScript 6, for Fastify service skeletons.
- Python 3 and uv, for scripting or Python services.
- rustup stable, Cargo, rustfmt, and clippy, for Axum invariant-heavy service
  skeletons.
- Docker CLI, kubectl, Helm, Skaffold, yq, jq, git-lfs, and rsync.

## Build

```bash
make build-devcontainer
```

The container workdir is `/workspace/train-ticket`, owned by the non-root
`vscode` user so ARL can clone or upload a repository snapshot into it.

## AgentM / ARL Worker Snapshot

Build a worker image that includes the current repository snapshot:

```bash
make build-agent-env-image
make check-agent-env-image
```

Use `train-ticket-agent-env:local` as `AGENTM_AGENT_ENV_IMAGE` when running an
AgentM scenario with the `agent_env` operations backend against local OrbStack
Kubernetes. The snapshot image keeps repository files owned by `vscode`, but
uses root as the default image user so ARL's workspace-seeding init container
can populate the sandbox volume.

## Smoke Check

```bash
.devcontainer/scripts/check.sh
```

The check verifies the base toolchain, the DDD document layout, and the
polyglot skeleton catalog.

## Full Repository Check

Use the devcontainer as the standard development environment for the rewrite.
From the host, run the authoritative strict validation inside the local image:

```bash
make check-devcontainer
```

Inside the container, run:

```bash
make check-strict
```

`make check-strict` is the authoritative skeleton validation gate because it
fails when required language toolchains are missing. Host `make check` is only an
adaptive smoke test and may skip languages that are not installed locally.
