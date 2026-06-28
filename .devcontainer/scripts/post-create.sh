#!/usr/bin/env bash
set -euo pipefail

if [ -S /var/run/docker.sock ] && command -v sudo >/dev/null 2>&1; then
  docker_gid="$(stat -c '%g' /var/run/docker.sock)"
  if [ "${docker_gid}" = "0" ]; then
    sudo usermod -aG root "${USER}" >/dev/null 2>&1 || true
  elif getent group docker >/dev/null 2>&1; then
    sudo groupmod -o -g "${docker_gid}" docker >/dev/null 2>&1 || true
  else
    sudo groupadd -g "${docker_gid}" docker >/dev/null 2>&1 || true
    sudo usermod -aG docker "${USER}" >/dev/null 2>&1 || true
  fi
fi

mkdir -p "${HOME}/.m2" "${HOME}/.cache" "${HOME}/.skaffold" "${HOME}/.npm"

echo "Train Ticket dev container is ready."
echo "Java: $(java -version 2>&1 | head -n 1)"
echo "Maven: $(mvn -version 2>/dev/null | head -n 1)"
echo "Node: $(node --version)"
echo "Python: $(python3 --version)"
echo "Helm: $(helm version --short 2>/dev/null)"
echo "Skaffold: $(skaffold version 2>/dev/null)"
echo "kubectl: $(kubectl version --client=true --output=yaml 2>/dev/null | awk '/gitVersion:/ {print $2; exit}')"

if [ -S /var/run/docker.sock ]; then
  if docker version --format '{{.Client.Version}} -> {{.Server.Version}}' >/tmp/train-ticket-docker-version 2>/dev/null; then
    echo "Docker: $(cat /tmp/train-ticket-docker-version)"
  else
    echo "Docker socket is mounted, but the current shell may need to be reloaded before docker is usable."
  fi
else
  echo "Docker socket is not mounted; skaffold image builds need Docker or a compatible remote builder."
fi
