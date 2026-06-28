# Train Ticket Dev Container

This container is the development and automation image for Train Ticket. It
contains the tools needed by an AgentM/ARL worker to edit, build, validate, and
deploy this repository:

- Debian 13/trixie with Java 17 and Maven for the Spring Boot reactor.
- Node 20 for `ts-ticket-office-service`.
- Python 3 with native build libraries for the Python services.
- Docker CLI, kubectl, Helm, Skaffold, yq, jq, git-lfs, and uv.

## Build

```bash
docker build -f .devcontainer/Dockerfile -t train-ticket-dev:local .
```

VS Code or the Dev Containers CLI can also open the repository directly from
`.devcontainer/devcontainer.json`.

The devcontainer mounts `/var/run/docker.sock` so `skaffold build --push=false`
can use the host or ARL-provided Docker daemon. If the ARL runtime uses a remote
builder instead, provide the matching Docker context before running image
builds.

When running the image directly, the container defaults to the non-root
`vscode` user. If Docker image builds need the host socket, add the socket group
seen inside the container or run that job as root. For Docker Desktop/OrbStack
this is often:

```bash
docker run --rm --group-add 0 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD:/workspace/train-ticket" \
  -w /workspace/train-ticket \
  train-ticket-dev:local .devcontainer/scripts/check.sh images
```

## Automation entrypoints

Use the check script as the stable AgentM/ARL contract:

```bash
.devcontainer/scripts/check.sh smoke    # toolchain + Helm dependency/lint/template
.devcontainer/scripts/check.sh package  # smoke + Maven package
.devcontainer/scripts/check.sh images   # Skaffold image build, requires Docker
.devcontainer/scripts/check.sh all      # package + Helm validation + image build
```

The Makefile wraps the same checks:

```bash
make smoke
make check
make skaffold-build
make deploy NS=ts PORT=30080
```

For daily release automation, a conservative one-day loop is:

1. Start from a clean checkout inside this image.
2. Run `make smoke` before edits to verify the environment.
3. Apply the code change.
4. Run `make check`.
5. Run `make skaffold-build` when Docker is available.
6. Deploy with `make deploy NS=<namespace> PORT=<nodePort>` or the equivalent
   `helm upgrade --install` command for the target cluster.
