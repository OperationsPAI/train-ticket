SHELL := /usr/bin/env bash

DEVCONTAINER_IMAGE ?= train-ticket-dev:local
AGENT_ENV_IMAGE ?= train-ticket-agent-env:local
OTEL_COLLECTOR_IMAGE ?= otel/opentelemetry-collector-contrib:latest
OBSERVABILITY_COMPOSE ?= platform/observability/docker-compose.yaml

# --- Deployment -------------------------------------------------------------
# The manifests reference train-ticket/<service>:local, so IMAGE_TAG and the
# manifests must agree; overriding it means editing the manifests too.
IMAGE_TAG ?= local
KIND_CLUSTER ?= train-ticket
# Default to the currently selected kubectl context rather than a hardcoded
# name. It used to default to kind-arl-test, which does not exist here (the
# real one is kind-train-ticket), so every deploy step would have failed on
# any cluster but that one. Override KCTX to target a specific context.
KCTX ?= $(shell kubectl config current-context 2>/dev/null)
NAMESPACE ?= train-ticket
K8S_DIR ?= deploy/k8s
ROLLOUT_TIMEOUT ?= 300s
# An empty context means "whatever kubeconfig selects"; --context "" is an error.
KUBECTL := kubectl $(if $(KCTX),--context $(KCTX),)
KUBENS := $(KUBECTL) -n $(NAMESPACE)

.PHONY: build-agent-env-image build-devcontainer check check-agent-env-image contract-lint check-devcontainer check-strict list-services observability-config observability-down observability-up observability-validate skeleton-check
.PHONY: deploy deploy-fast deploy-images deploy-apply deploy-wait deploy-db-bootstrap deploy-roll deploy-services deploy-seed deploy-check e2e smoke

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

# ---------------------------------------------------------------------------
# Deployment
#
# `make deploy` takes a running kind cluster to a verified stack in one
# command. Every step is idempotent, so re-running is safe and is the intended
# way to apply a change.
#
#   make deploy       full: build 38 images + load into kind, then deploy
#   make deploy-fast   manifest-only: skip the build, reuse loaded images
#
# WHY BUILDING IS SKIPPABLE
# A 38-service rebuild dominates the wall clock, and the overwhelmingly common
# change -- editing a manifest, a probe, a resource limit, the loadgen config
# -- does not change a single image. Forcing a rebuild for those would make the
# one-command path slow enough that people would go back to running the steps
# by hand, which is the problem this is meant to remove. `deploy-fast` is the
# escape hatch; `deploy` remains the safe default, and Docker layer caching
# already makes an unchanged rebuild cheap.
# ---------------------------------------------------------------------------

# These steps are strictly ordered -- applying before the images are loaded,
# or seeding before the databases exist, silently produces a broken stack.
# Each step declares its predecessor as a prerequisite rather than relying on
# listing order, which `make -j` does not honour.
#
# .NOTPARALLEL is belt-and-braces on top of that explicit chain, so a stray
# `make -j deploy` cannot interleave the stages. It is global (GNU make has no
# per-target form here), so it also serialises `check`'s two prerequisites --
# two short python scripts, so the cost is negligible.
.NOTPARALLEL:

deploy: deploy-check
	@echo
	@echo "=============================================================="
	@echo "deploy: COMPLETE -- stack is up, seeded and smoke-verified."
	@echo "=============================================================="

# Same pipeline minus the image build. Use after a manifest-only change.
# DEPLOY_SKIP_IMAGES short-circuits the build step for the whole chain.
deploy-fast:
	@$(MAKE) --no-print-directory DEPLOY_SKIP_IMAGES=1 deploy-check
	@echo
	@echo "deploy-fast: COMPLETE (images were not rebuilt)."

deploy-images:
ifdef DEPLOY_SKIP_IMAGES
	@echo "== deploy: skipping image build (DEPLOY_SKIP_IMAGES=1)"
else
	@echo "== deploy: building and loading images (tag $(IMAGE_TAG), cluster $(KIND_CLUSTER))"
	KIND_CLUSTER=$(KIND_CLUSTER) LOAD_INTO_KIND=1 deploy/build-images.sh $(IMAGE_TAG)
endif

deploy-apply: deploy-images
	@echo "== deploy: applying manifests"
	@# A completed Job's pod template is immutable, so re-applying a changed
	@# one is rejected by the API server. Deleting first is what makes the
	@# bootstrap actually re-run on every deploy instead of being skipped as
	@# "unchanged" -- the whole point of the Job.
	-$(KUBENS) delete job db-bootstrap --ignore-not-found --wait=true
	$(KUBECTL) apply -k $(K8S_DIR)

deploy-wait: deploy-apply
	@echo "== deploy: waiting for infrastructure"
	@# Postgres and Redis first: a service that loses the race to its database
	@# stays permanently unready, which is failure mode #4 in the runbook.
	$(KUBENS) rollout status deployment/postgres --timeout=$(ROLLOUT_TIMEOUT)
	$(KUBENS) rollout status deployment/redis --timeout=$(ROLLOUT_TIMEOUT)

deploy-db-bootstrap: deploy-wait
	@echo "== deploy: bootstrapping databases"
	@# Runs before the services are waited on, so databases exist before the
	@# services' startup migrations need them.
	$(KUBENS) wait --for=condition=complete job/db-bootstrap --timeout=$(ROLLOUT_TIMEOUT) \
	  || ( echo "db-bootstrap FAILED -- logs follow:" >&2; $(KUBENS) logs job/db-bootstrap --tail=100 >&2; exit 1 )
	$(KUBENS) logs job/db-bootstrap --tail=5

deploy-roll: deploy-db-bootstrap
ifdef DEPLOY_SKIP_IMAGES
	@echo "== deploy: skipping rollout (images were not rebuilt)"
else
	@echo "== deploy: rolling business services onto the freshly built images"
	@# The manifests pin :local, so a rebuild leaves the pod spec byte-identical
	@# and `apply -k` reports "unchanged" -- the running pods keep the OLD image
	@# forever. repair-unready.sh does not cover this either: it only restarts
	@# deployments that are already unready, and after a successful rebuild they
	@# are all healthy. Without this step `make deploy` builds 39 images and
	@# deploys none of them, which is exactly what happened on the first real
	@# run of this pipeline. Infrastructure is excluded: postgres and redis use
	@# upstream images that a rebuild never touches, and bouncing postgres here
	@# would undo the bootstrap that just ran.
	$(KUBENS) rollout restart $$($(KUBENS) get deploy -o name \
	  | grep -vE 'postgres|redis|jaeger|mailpit|otel-collector')
endif

deploy-services: deploy-roll
	@echo "== deploy: waiting for services (repairing any that lost the database race)"
	@# Failure mode #4: a service whose startup migration ran before its
	@# database existed stays permanently unready and needs a restart. This
	@# restarts ONLY the deployments that are actually not fully ready, then
	@# waits. Restarting everything unconditionally would bounce postgres --
	@# which carries the same part-of label -- and would make the second run
	@# slow; on an already-healthy cluster this loop restarts nothing.
	KCTX=$(KCTX) NAMESPACE=$(NAMESPACE) ROLLOUT_TIMEOUT=$(ROLLOUT_TIMEOUT) deploy/repair-unready.sh

deploy-seed: deploy-services
	@echo "== deploy: seeding reference data"
	KCTX=$(KCTX) deploy/seed.sh

deploy-check: deploy-seed
	@echo "== deploy: smoke verification"
	KCTX=$(KCTX) NAMESPACE=$(NAMESPACE) deploy/smoke.sh

# Smoke verification on demand, against an already-deployed stack.
smoke:
	KCTX=$(KCTX) NAMESPACE=$(NAMESPACE) deploy/smoke.sh

# The full e2e suite. Separate from deploy on purpose: these are the tests,
# not the deployment. Run after `make deploy`.
e2e:
	@set -e; for script in deploy/e2e/[0-9]*.sh; do \
	  echo "== $$script"; \
	  "$$script"; \
	done
