NS ?= ts
PORT ?= 30080
CHART_DIR ?= manifests/helm/trainticket
MAVEN_THREADS ?= 1.5C
MAVEN_SKIP_TESTS ?= true

.PHONY: smoke check package helm-deps helm-lint skaffold-build deploy upgrade-chart otel-agent

smoke:
	.devcontainer/scripts/check.sh smoke

check:
	.devcontainer/scripts/check.sh package

package:
	mvn -B -T $(MAVEN_THREADS) clean package -Dmaven.test.skip=$(MAVEN_SKIP_TESTS)

helm-deps:
	helm dependency build $(CHART_DIR)

helm-lint:
	.devcontainer/scripts/check.sh smoke

skaffold-build:
	.devcontainer/scripts/check.sh images

deploy:
	@if helm status $(NS) -n $(NS) >/dev/null 2>&1; then \
		echo "Uninstalling existing $(NS) release"; \
		helm uninstall $(NS) -n $(NS); \
		sleep 5; \
	else \
		echo "No existing $(NS) release found"; \
	fi; \
	helm install $(NS) $(CHART_DIR) --create-namespace -n $(NS) \
		--set global.monitoring=opentelemetry \
		--set global.otelcollector="http://opentelemetry-collector-deployment.monitoring:4317" \
		--set skywalking.enabled=false \
		--set global.image.tag=637600ea \
		--set services.tsUiDashboard.nodePort=$(PORT)

upgrade-chart:
	cd $(CHART_DIR) && \
		helm dependency update


otel-agent:
	cd otel-java-agent && bash build.sh
