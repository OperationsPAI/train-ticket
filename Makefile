SHELL := /usr/bin/env bash

DEVCONTAINER_IMAGE ?= train-ticket-dev:local
AGENT_ENV_IMAGE ?= train-ticket-agent-env:local
OTEL_COLLECTOR_IMAGE ?= otel/opentelemetry-collector-contrib:latest
OBSERVABILITY_COMPOSE ?= platform/observability/docker-compose.yaml

.PHONY: build-agent-env-image build-devcontainer check check-agent-env-image contract-lint check-devcontainer check-strict list-services observability-config observability-down observability-up observability-validate skeleton-check

check: skeleton-check contract-lint

contract-lint:
	python3 scripts/contract_lint.py

build-devcontainer:
	docker build -f .devcontainer/Dockerfile -t $(DEVCONTAINER_IMAGE) .

build-agent-env-image: build-devcontainer
	docker build -f .devcontainer/agent-env.Dockerfile --build-arg BASE_IMAGE=$(DEVCONTAINER_IMAGE) -t $(AGENT_ENV_IMAGE) .

check-agent-env-image:
	docker run --rm $(AGENT_ENV_IMAGE) bash -lc 'make check-strict'

check-devcontainer:
	docker run --rm -v "$$PWD:/workspace/train-ticket" -w /workspace/train-ticket $(DEVCONTAINER_IMAGE) bash -lc 'make check-strict'

check-strict:
	python3 scripts/check-skeleton.py --strict

skeleton-check:
	python3 scripts/check-skeleton.py

list-services:
	python3 scripts/list-services.py

observability-config:
	docker compose -f $(OBSERVABILITY_COMPOSE) config

observability-validate:
	docker run --rm -v "$$PWD/platform/observability/otel-collector.yaml:/etc/otelcol-contrib/config.yaml:ro" $(OTEL_COLLECTOR_IMAGE) validate --config=/etc/otelcol-contrib/config.yaml

observability-up:
	docker compose -f $(OBSERVABILITY_COMPOSE) up -d

observability-down:
	docker compose -f $(OBSERVABILITY_COMPOSE) down
