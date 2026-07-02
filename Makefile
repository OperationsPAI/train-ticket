SHELL := /usr/bin/env bash

DEVCONTAINER_IMAGE ?= train-ticket-dev:local
AGENT_ENV_IMAGE ?= train-ticket-agent-env:local

.PHONY: build-agent-env-image build-devcontainer check check-agent-env-image check-devcontainer check-strict skeleton-check list-services

check: skeleton-check

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
