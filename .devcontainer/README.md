# Train Ticket Greenfield Dev Container

This image is a neutral development and AgentM/ARL worker environment for the
greenfield DDD rebuild. It intentionally does not assume the old Maven reactor,
Helm chart, or Skaffold project still exists.

Included tools:

- Java 17 and Maven, for Java/Kotlin implementation options.
- Node 20, for TypeScript implementation options.
- Python 3 and uv, for scripting or Python services.
- Docker CLI, kubectl, Helm, Skaffold, yq, jq, git-lfs, and rsync.

## Build

```bash
docker build -f .devcontainer/Dockerfile -t train-ticket-dev:local .devcontainer
```

The container workdir is `/workspace/train-ticket`, owned by the non-root
`vscode` user so ARL can clone or upload a repository snapshot into it.

## Smoke Check

```bash
.devcontainer/scripts/check.sh
```

The check only verifies the base toolchain and the DDD document layout. Build,
test, deployment, and CI commands should be added by the work package that
introduces the first real implementation stack.
