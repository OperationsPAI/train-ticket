SHELL := /usr/bin/env bash

DEVCONTAINER_IMAGE ?= train-ticket-dev:local
AGENT_ENV_IMAGE ?= train-ticket-agent-env:local
# Must match otelCollector.image.tag in deploy/helm/train-ticket/values.yaml.
# Pinned rather than :latest so `make observability-validate` checks the config
# against the version that actually runs -- the clickhouse exporter's schema has
# changed across releases, so validating against a floating tag can pass for a
# config the deployed collector rejects.
OTEL_COLLECTOR_IMAGE ?= otel/opentelemetry-collector-contrib:0.112.0
OBSERVABILITY_COMPOSE ?= platform/observability/docker-compose.yaml

# --- Deployment -------------------------------------------------------------
# The tag the images are BUILT with. It is passed to helm as global.imageTag
# (see deploy-apply) rather than only being consumed by the build, because
# otherwise the two halves disagree silently: `make IMAGE_TAG=foo deploy` would
# build train-ticket/*:foo and then install a release still pinned to :local,
# which is an ImagePullBackOff with no other symptom. Overriding it now changes
# both sides at once.
IMAGE_TAG ?= local
# The tag the images were BUILT with locally. Normally the same as IMAGE_TAG --
# build-images.sh tags with one name and the chart deploys with it. They are
# separable because they are genuinely different things: pushing an existing
# build (say :local, built before the registry layout changed) under a fresh
# deployed tag is `make push-images LOCAL_TAG=local IMAGE_TAG=20260913`, and it
# avoids rebuilding 39 images to rename them.
LOCAL_TAG ?= $(IMAGE_TAG)
KIND_CLUSTER ?= train-ticket
# Default to the currently selected kubectl context rather than a hardcoded
# name. It used to default to kind-arl-test, which does not exist here (the
# real one is kind-train-ticket), so every deploy step would have failed on
# any cluster but that one. Override KCTX to target a specific context.
KCTX ?= $(shell kubectl config current-context 2>/dev/null)
NAMESPACE ?= train-ticket
# --- Helm (the single deployment path; kustomize was retired) ---------------
HELM_RELEASE ?= train-ticket
HELM_CHART ?= deploy/helm/train-ticket
# values-kind.yaml carries the local-cluster bits: locally built
# train-ticket/*:local images that exist only on the node (so
# imagePullPolicy must stay IfNotPresent), and kind's single `standard`
# StorageClass. Override HELM_VALUES for a real cluster (values-prod.yaml).
HELM_VALUES ?= deploy/helm/values-kind.yaml
HELM_TIMEOUT ?= 15m
ROLLOUT_TIMEOUT ?= 300s
# An empty context means "whatever kubeconfig selects"; --context "" is an error.
KUBECTL := kubectl $(if $(KCTX),--context $(KCTX),)
KUBENS := $(KUBECTL) -n $(NAMESPACE)

.PHONY: build-agent-env-image build-devcontainer check check-agent-env-image contract-lint check-devcontainer check-strict list-services observability-config observability-down observability-up observability-validate skeleton-check
.PHONY: deploy deploy-fast deploy-images deploy-apply deploy-db-bootstrap deploy-roll deploy-services deploy-seed deploy-check e2e smoke push-images deploy-acr deploy-acr-apply

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
	@echo "== deploy: installing/upgrading the Helm release"
	@# helm upgrade --install is idempotent and does the work that took four
	@# separate kustomize stages: it applies, rolls Deployments whose spec or
	@# config hash changed, runs the db-bootstrap hook, and --wait blocks until
	@# every Deployment is Available.
	@#
	@# Deliberately NOT --force. --force replaces resources instead of patching
	@# them, which (a) does not help here anyway -- the manifests pin :local, so
	@# after `kind load` swaps the image behind that tag the rendered spec is
	@# byte-identical, and replacing an identical Deployment spec produces no new
	@# ReplicaSet, so the pods keep the OLD image -- and (b) extends replace
	@# semantics to Services and PVCs, which is a much bigger hammer than the
	@# problem needs. deploy-roll handles the stale-image case explicitly.
	helm upgrade --install $(HELM_RELEASE) $(HELM_CHART) \
	  -f $(HELM_VALUES) \
	  --set global.imageTag=$(IMAGE_TAG) \
	  --namespace $(NAMESPACE) --create-namespace \
	  $(if $(KCTX),--kube-context $(KCTX),) \
	  --wait --timeout $(HELM_TIMEOUT)

deploy-db-bootstrap: deploy-apply
	@echo "== deploy: verifying databases"
	@# The chart creates them: templates/db-bootstrap.yaml is a per-shard
	@# pre-upgrade/post-install hook, so helm has already run it (and failed the
	@# release if it errored) by the time deploy-apply returns.
	@#
	@# This verifies independently anyway, because the failure it guards against
	@# is silent: a missing database produces no signal until some service's
	@# first query, and a service whose readiness probe does not touch Postgres
	@# reports healthy the whole time. That is exactly how group_booking stayed
	@# missing for an unknown length of time. It also catches the case the hook
	@# structurally cannot: a templating bug that renders a DSN pointing at a
	@# database no shard's `databases` list contains, since the check reads the
	@# rendered DSNs rather than the values the hook was generated from.
	KCTX=$(KCTX) NAMESPACE=$(NAMESPACE) deploy/verify-databases.sh

deploy-roll: deploy-db-bootstrap
ifdef DEPLOY_SKIP_IMAGES
	@echo "== deploy: skipping rollout (images were not rebuilt)"
else
	@echo "== deploy: rolling business services onto the freshly built images"
	@# The manifests pin :local, so a rebuild leaves the pod spec byte-identical
	@# and helm reports the release unchanged -- the running pods keep the OLD
	@# image forever. repair-unready.sh does not cover this either: it only
	@# restarts deployments that are already unready, and after a successful
	@# rebuild they are all healthy. Without this step `make deploy` builds 39
	@# images and deploys none of them, which is exactly what happened on the
	@# first real run of this pipeline. Infrastructure is excluded: it runs
	@# upstream images that a rebuild never touches, and bouncing postgres here
	@# would restart the shard the bootstrap hook just seeded.
	$(KUBENS) rollout restart $$($(KUBENS) get deploy -o name \
	  | grep -vE 'postgres|redis|jaeger|mailpit|otel-collector')
	$(KUBENS) rollout status --timeout=$(ROLLOUT_TIMEOUT) $$($(KUBENS) get deploy -o name \
	  | grep -vE 'postgres|redis|jaeger|mailpit|otel-collector') || true
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

# --- Real cluster, via a registry -------------------------------------------
# The counterpart to `make deploy`, which is kind-only: it ends in `kind load`
# and never touches a registry, so it cannot deploy anywhere but a kind cluster
# on this host. This path pushes to a registry instead, and is what a remote
# cluster needs.
#
# The values files are the profile, not flags: values-acr.yaml carries the
# registry, the single-repository image layout, the StorageClass and the
# loadgen sizing, and every derived list (verify-databases, seed, smoke) renders
# through the same HELM_VALUES, so the deployed and verified definitions cannot
# drift.
#
# Pushing is a separate target rather than a prerequisite, deliberately: the
# images are 9 GB and are unchanged by a manifest or values edit, which is the
# same reasoning that gave `deploy` a `deploy-fast` escape hatch.
HELM_VALUES_ACR ?= deploy/helm/values-prod.yaml deploy/helm/values-acr.yaml
# The probe pod's image. Upstream is curlimages/curl:8.10.1, which a cluster
# without a route to auth.docker.io cannot pull; it is mirrored into the same
# repository as the services by push-images.sh's infra handling.
SMOKE_IMAGE ?= registry.cn-shenzhen.aliyuncs.com/lincyaw/trainticket:curl-8.10.1

deploy-acr-apply:
	@echo "== deploy-acr: installing/upgrading the Helm release"
	helm upgrade --install $(HELM_RELEASE) $(HELM_CHART) \
	  $(foreach f,$(HELM_VALUES_ACR),-f $(f)) \
	  --namespace $(NAMESPACE) --create-namespace \
	  $(if $(KCTX),--kube-context $(KCTX),) \
	  --set global.imageTag=$(IMAGE_TAG) \
	  --wait --timeout $(HELM_TIMEOUT)

deploy-acr: deploy-acr-apply
	@echo "== deploy-acr: verifying databases"
	HELM_VALUES="$(HELM_VALUES_ACR)" KCTX=$(KCTX) NAMESPACE=$(NAMESPACE) deploy/verify-databases.sh
	@echo "== deploy-acr: waiting for services (repairing any that lost the database race)"
	HELM_VALUES="$(HELM_VALUES_ACR)" KCTX=$(KCTX) NAMESPACE=$(NAMESPACE) ROLLOUT_TIMEOUT=$(ROLLOUT_TIMEOUT) deploy/repair-unready.sh
	@echo "== deploy-acr: seeding reference data"
	HELM_VALUES="$(HELM_VALUES_ACR)" KCTX=$(KCTX) NAMESPACE=$(NAMESPACE) deploy/seed.sh
	@echo "== deploy-acr: smoke verification"
	HELM_VALUES="$(HELM_VALUES_ACR)" KCTX=$(KCTX) NAMESPACE=$(NAMESPACE) SMOKE_IMAGE=$(SMOKE_IMAGE) deploy/smoke.sh
	@echo
	@echo "=============================================================="
	@echo "deploy-acr: COMPLETE -- stack is up, seeded and smoke-verified."
	@echo "=============================================================="

# Smoke verification on demand, against an already-deployed stack.
smoke:
	KCTX=$(KCTX) NAMESPACE=$(NAMESPACE) deploy/smoke.sh

# Push the built service images to the registry the release names.
#
# The real-cluster counterpart to deploy-images: on kind the images reach the
# node through `kind load`, which needs no registry, so `make deploy` has no
# push in it. A real cluster has no such shortcut and this is the step that puts
# them in a registry both this host and the cluster can reach.
#
# HELM_VALUES decides WHERE they go -- the chart renders the full reference, so
# the registry, the organization and the repository layout are all chart
# configuration, not flags here. A real-cluster deploy therefore names its
# values files on both this target and the helm call:
#
#   make push-images HELM_VALUES="deploy/helm/values-prod.yaml deploy/helm/values-acr.yaml" \
#     LOCAL_TAG=local IMAGE_TAG=20260913 NAMESPACE=train-ticket-prod
#
# DRY_RUN=1 resolves the whole list and prints the mapping without pushing.
push-images:
	LOCAL_TAG=$(LOCAL_TAG) IMAGE_TAG=$(IMAGE_TAG) NAMESPACE=$(NAMESPACE) \
	  DRY_RUN=$(DRY_RUN) HELM_VALUES="$(HELM_VALUES)" deploy/push-images.sh

# The full e2e suite. Separate from deploy on purpose: these are the tests,
# not the deployment. Run after `make deploy`.
#
# LOADGEN IS PAUSED FOR THE DURATION
# The suite asserts on specific orders it just created, and several of its
# assertions are timing-bounded (poll for a saga, wait for an event to appear on
# a stream). A loadgen running 16 scalper workers and a full customer mix
# saturates those same paths, so assertions fail for want of scheduling rather
# than for want of correctness -- "no saga found for order" was the most common
# symptom, on a cluster where sagas were being created at 1300 per five minutes.
# Only 12-restart and seed.sh paused it before, which meant 21 of the 23 scripts
# ran under full load. Pausing here covers the whole suite in one place.
#
# INT/TERM as well as EXIT, because a bare EXIT trap does not fire when make is
# signalled, and leaving loadgen at 0 replicas is invisible: it is not an error
# state, there is no pod to look unhealthy, and the next run reads 0 as "already
# paused" and does not restore it either.
#
# FAILURES ARE COUNTED, NOT INFERRED FROM EXIT CODES
# The scripts report a failed assertion with `bad` and still exit 0, so the
# previous `set -e` loop reported every one of 23 scripts as passing while the
# log held 109 failed assertions. This greps the assertion marker instead, which
# is what actually determines whether the suite passed.
e2e:
	@set -u; \
	lg=$$($(KUBENS) get deploy loadgen -o jsonpath='{.spec.replicas}' 2>/dev/null || echo ''); \
	resume() { \
	  if [ -n "$$lg" ] && [ "$$lg" != "0" ]; then \
	    $(KUBENS) scale deploy loadgen --replicas="$$lg" >/dev/null 2>&1 \
	      && echo "e2e: loadgen resumed (replicas=$$lg)" \
	      || echo "e2e: WARNING could not resume loadgen -- scale it back by hand" >&2; \
	  fi; \
	}; \
	trap resume EXIT INT TERM; \
	if [ -n "$$lg" ] && [ "$$lg" != "0" ]; then \
	  $(KUBENS) scale deploy loadgen --replicas=0 >/dev/null 2>&1 || true; \
	  $(KUBENS) wait --for=delete pod -l app.kubernetes.io/name=loadgen --timeout=90s >/dev/null 2>&1 || true; \
	  echo "e2e: loadgen paused (was replicas=$$lg)"; \
	fi; \
	log=$$(mktemp); failed=''; \
	for script in deploy/e2e/[0-9]*.sh; do \
	  echo "== $$script"; \
	  : > "$$log"; \
	  "$$script" 2>&1 | tee "$$log"; \
	  n=$$(grep -c '✗' "$$log" || true); \
	  if [ "$$n" -gt 0 ]; then \
	    echo "   -> $$n failed assertion(s) in $$script"; \
	    failed="$$failed $$(basename $$script):$$n"; \
	  fi; \
	done; \
	rm -f "$$log"; \
	if [ -n "$$failed" ]; then \
	  echo; echo "e2e FAILED --$$failed" >&2; exit 1; \
	fi; \
	echo; echo "e2e: all scripts passed with zero failed assertions."
