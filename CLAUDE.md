# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Train Ticket is a microservices-based train ticket booking system built with Spring Boot 3.2.0 and Java 17. The system consists of 40+ independent microservices that handle different aspects of ticket booking, user management, payment processing, and administrative functions.

## Technology Stack

- **Java**: 17
- **Spring Boot**: 3.2.0
- **Spring Cloud**: 2023.0.0
- **Spring Cloud Alibaba**: 2023.0.1.0
- **Database**: MySQL 8.0.33 with JPA/Hibernate
- **Observability**: OpenTelemetry, SkyWalking support
- **API Documentation**: SpringDoc OpenAPI 2.3.0
- **Container Orchestration**: Kubernetes with Helm
- **Build Tools**: Maven, Skaffold, Docker

## Build Commands

### Build the entire project
```bash
mvn clean package -Dmaven.test.skip=true
```

### Build a specific service
```bash
cd ts-<service-name>
mvn clean package -Dmaven.test.skip=true
```

### Run tests with coverage
```bash
mvn test
# Coverage reports generated in target/jacoco-ut/
```

### Build Docker images
```bash
skaffold build --default-repo=<your-registry>
# Example: skaffold build --default-repo=docker.io/myuser
```

### Build OpenTelemetry agent
```bash
cd otel-java-agent && bash build.sh
```

## Code Quality

We use pre-commit hooks to maintain Java code quality. The configuration includes:
- **Checkstyle**: Enforces Google Java Style Guide
- **PMD**: Static code analysis for potential bugs
- **Pretty-format-java**: Automatic code formatting
- **Maven hooks**: Compile and test validation

To set up pre-commit:
```bash
# Install pre-commit
pip install pre-commit

# Install git hooks
pre-commit install

# Run on all files
pre-commit run --all-files

# Run specific checks
pre-commit run --all-files --show-diff-on-failure --color=always checkstyle
pre-commit run --all-files --show-diff-on-failure --color=always pmd
```

Note: The pre-commit hooks will automatically run Maven compile and test phases to ensure code quality before commits.

## Deployment Commands

### Build Helm dependencies (required before first deployment)
```bash
helm dependency build manifests/helm/generic_service
```

### Deploy to Kubernetes
```bash
# Basic deployment
helm install ts manifests/helm/generic_service -n ts --create-namespace \
  --set global.monitoring=opentelemetry \
  --set skywalking.enabled=false \
  --set global.image.tag=<image-tag>

# Using Makefile (with custom namespace and port)
make deploy NS=ts PORT=30080

# Upgrade existing deployment
helm upgrade ts manifests/helm/generic_service -n ts \
  --set global.monitoring=opentelemetry \
  --set global.image.tag=<new-tag>

# Uninstall
helm uninstall ts -n ts
```

## Architecture

### Microservices Structure

The system follows a domain-driven microservices architecture where each service is independently deployable and has its own database schema. Services are organized by business capability:

**Core Booking Services:**
- `ts-preserve-service` / `ts-preserve-other-service`: Ticket reservation (normal/high-speed trains)
- `ts-travel-service` / `ts-travel2-service`: Travel route management (normal/high-speed trains)
- `ts-order-service` / `ts-order-other-service`: Order management (normal/high-speed trains)
- `ts-seat-service`: Seat allocation and management
- `ts-ticket-office-service`: Ticket office operations

**User & Authentication:**
- `ts-auth-service`: Authentication and JWT token management
- `ts-user-service`: User profile management
- `ts-verification-code-service`: Verification code generation
- `ts-contacts-service`: User contact information

**Payment & Financial:**
- `ts-payment-service`: External payment processing
- `ts-inside-payment-service`: Internal payment handling
- `ts-assurance-service`: Travel insurance

**Food & Delivery:**
- `ts-food-service`: Food menu management
- `ts-station-food-service`: Station food store management
- `ts-train-food-service`: Train food service
- `ts-food-delivery-service`: Food delivery coordination
- `ts-delivery-service`: General delivery service

**Infrastructure Services:**
- `ts-config-service`: Configuration management
- `ts-basic-service`: Basic data services (stations, trains, routes, prices)
- `ts-station-service`: Station information
- `ts-train-service`: Train information
- `ts-route-service`: Route management
- `ts-price-service`: Pricing logic

**Administrative:**
- `ts-admin-basic-info-service`: Admin basic info management
- `ts-admin-order-service`: Admin order management
- `ts-admin-route-service`: Admin route management
- `ts-admin-travel-service`: Admin travel management
- `ts-admin-user-service`: Admin user management

**Supporting Services:**
- `ts-cancel-service`: Order cancellation
- `ts-rebook-service`: Ticket rebooking
- `ts-execute-service`: Order execution
- `ts-wait-order-service`: Waiting list management
- `ts-consign-service` / `ts-consign-price-service`: Luggage consignment
- `ts-security-service`: Security checks
- `ts-notification-service`: Notifications
- `ts-travel-plan-service` / `ts-route-plan-service`: Travel planning
- `ts-avatar-service`: User avatar management
- `ts-voucher-service`: Voucher management
- `ts-news-service`: News and announcements

### Common Module (ts-common)

The `ts-common` module provides shared functionality across all services:
- **JWT Authentication**: `edu.fudan.common.security.jwt.JWTUtil`, `JWTFilter`
- **API Configuration**: `OpenApiConfig`, `SwaggerConfig`
- **REST Client**: `RestTemplateConfig` for inter-service communication
- **Common Entities**: Shared domain objects like `TripInfo`, `Order`, `FoodOrder`
- **Utilities**: `JsonUtils`, `StringUtils`, `Response` wrapper

### Service Communication

Services communicate via REST APIs using `RestTemplate`. Each service exposes its API through controllers and is documented with SpringDoc OpenAPI (accessible at `/swagger-ui.html` on each service).

### Database Architecture

Each service has its own MySQL database schema, following the database-per-service pattern. Database connection details are configured via environment variables:
- `<SERVICE>_MYSQL_HOST`
- `<SERVICE>_MYSQL_PORT`
- `<SERVICE>_MYSQL_DATABASE`
- `<SERVICE>_MYSQL_USER`
- `<SERVICE>_MYSQL_PASSWORD`

### Service Structure Pattern

All services follow a consistent structure:
```
ts-<service-name>/
├── src/main/java/<package>/
│   ├── <Service>Application.java    # Spring Boot entry point
│   ├── controller/                  # REST controllers
│   ├── service/                     # Business logic
│   ├── repository/                  # JPA repositories
│   ├── entity/                      # Domain entities
│   ├── config/                      # Configuration classes
│   └── init/                        # Data initialization
├── src/main/resources/
│   └── application.yml              # Service configuration
├── Dockerfile                       # Multi-stage build with OpenTelemetry
└── pom.xml                          # Maven dependencies
```

### Observability

Services are instrumented with OpenTelemetry for distributed tracing. The Dockerfile includes the OpenTelemetry Java agent for automatic instrumentation. Monitoring can be configured via Helm values:
- `global.monitoring=opentelemetry` or `skywalking`
- `global.otelcollector` for OpenTelemetry collector endpoint

## Development Workflow

1. Make code changes in the relevant service directory
2. Build the service: `mvn clean package -Dmaven.test.skip=true`
3. Build Docker image: `skaffold build --default-repo=<registry>`
4. Deploy/upgrade: `helm upgrade ts manifests/helm/generic_service -n ts --set global.image.tag=<new-tag>`

## Testing

- Unit tests use JUnit and Spring Boot Test
- Code coverage reports generated by JaCoCo in `target/jacoco-ut/`
- Run tests: `mvn test`
- Skip tests during build: `mvn clean package -Dmaven.test.skip=true`

## Important Notes

- All services run on port 8080 internally (mapped via Kubernetes services)
- JWT tokens are used for authentication across services
- Services use Spring Boot 3.x with Jakarta EE (not javax)
- Lombok is used extensively for reducing boilerplate code
- The UI dashboard (`ts-ui-dashboard`) is the frontend application

<!-- auto-harness:begin -->
## Core principles

Three axioms govern all work. Fall back to these when a skill's instructions do not cover a situation:

1. **Quality over quantity** - a few things done well beats many done poorly. Applies to tests, observations, skills, code, docs, experiments, ideas. If you cannot say why each item exists, there are too many.
2. **Surface problems early** - fail fast, validate before investing, outline before drafting. Never hide complexity to make something look simpler.
3. **Deliberate execution** - every decision traceable to a reason. Understand before acting; validate manually before automating; measure before optimizing; consider removing before adding.

Full text: `/Users/bytedance/.codex/repos/autoharness/references/principles.md`.

## North-star targets

1. **Build and deployment gate health** - repo-level smoke/package/image checks remain runnable and regressions are caught before handoff.
   Measure: `make smoke`; `make check`; `make skaffold-build` when Dockerfiles, Skaffold, or image/deploy behavior changes.
   Mechanism: script.
   Current baseline: `make smoke` failed during setup on 2026-06-28 because `skaffold` is missing from the local environment.

2. **Refactor dependency-chain clarity** - every cross-service change has an explicit affected-service list and dependency rationale.
   Measure: fixed agent review prompt: "List affected services, upstream/downstream dependencies, config/Helm/Dockerfile touchpoints, and any unexpected coupling introduced by this change."
   Mechanism: agent, with human confirmation for broad refactors.
   Current baseline: dependency-chain map and `project-index.yaml` are intentionally deferred until the upcoming dependency-chain reconstruction work.

3. **Observability does not regress** - OpenTelemetry, metrics, Dockerfile agent wiring, and Helm monitoring values stay consistent when services or deployment manifests change.
   Measure: `make smoke` for Helm rendering plus agent review of observability-related file changes.
   Mechanism: script + agent; human/runtime validation when deployed to Kubernetes.
   Current baseline: repository has OpenTelemetry support in service Dockerfiles, Helm values, and recent commits; runtime trace/metric validation was not run during setup.

Secondary: prefer small, service-local changes with explicit dependency updates over broad rewrites whose blast radius is hard to inspect.

## Observation inventory

Automated checks:
- `make smoke` - toolchain and Helm validation; run after environment, chart, or deployment changes.
- `make check` - smoke plus Maven package; run before declaring substantial code changes done.
- `pre-commit run --all-files` - formatting, file hygiene, PMD, Maven compile/test hooks; run before commits when feasible.
- `make skaffold-build` - local image build; run when Dockerfile, Skaffold, image, or deployment behavior changes and Docker is available.

Agent checks:
- For cross-service changes, review service boundaries, upstream/downstream dependencies, and whether config, Helm, Dockerfile, and observability wiring stayed synchronized.
- For refactors, verify the change reduces or clarifies coupling without changing behavior unless the behavior change is explicitly requested.

Human checks:
- Use CI results as the default external gate.
- For deployment-sensitive work, validate in kind/minikube or the target Kubernetes environment and inspect OpenTelemetry traces/metrics when the change touches instrumentation or runtime wiring.

Priority:
- Keep microservice build/deploy reliability first.
- Preserve observability behavior while refactoring.
- Build the dependency-chain map before introducing a formal requirements index.

## Dev-loop stages

| Stage | Command | Notes |
|-------|---------|-------|
| Scope | `git status --short` | Confirm worktree state and avoid mixing unrelated changes. |
| Service build | `mvn -pl <module> -am clean package -Dmaven.test.skip=true` | Prefer for focused Java service changes. |
| Full package | `make check` | Runs tool verification, Maven package, and Helm validation through `.devcontainer/scripts/check.sh`. |
| Tests | `mvn test` | Current setup found no `src/test` files; add focused tests as behavior is clarified. |
| Pre-commit | `pre-commit run --all-files` | Runs Java formatting, PMD, Maven compile/test hooks, and file hygiene. |
| Helm smoke | `make smoke` | Requires `skaffold`, Helm, kubectl, yq, jq, and other devcontainer tools. |
| Images | `make skaffold-build` | Requires Docker and Skaffold; run for image/deployment changes. |

## Iteration tracking

- Progress log: `progress.tsv` - dev-loop records keep/discard decisions and metric values.
- Decision log: `decisions.md` - long-horizon or broad refactor decisions go here when work spans multiple sessions.

## Project conventions

- Configure and reason from the repository root unless a task explicitly targets one service.
- Use the existing Makefile and `.devcontainer/scripts/check.sh` as the source of truth for automation commands.
- Use `manifests/helm/trainticket` as the active Helm chart path. Treat older `manifests/helm/generic_service` references as stale unless verified.
- Prefer Java 17 / Spring Boot 3.2 conventions for Java services; note that one legacy CI workflow still configures JDK 8.
- Keep OpenTelemetry and deployment wiring in sync with service changes: Dockerfile agent setup, Helm values, and service configuration should be checked together.
- `project-index.yaml` is intentionally deferred. Create it later after the dependency-chain reconstruction pass, then add the mandatory requirements-index rules.

## Active skills

- `/autoharness:guide` - session-start methodology briefing from active targets and profile.
- `/autoharness:north-star` - keeps work tied to the build/deploy, dependency clarity, and observability targets above.
- `/autoharness:dev-loop` - complete implement/test/review/measure loop using this repo's Makefile and validation gates.
- `/autoharness:diagnosis` - root-cause analysis for build, deployment, dependency, and observability failures.
- `/autoharness:existing-project` - recover requirements and service dependency structure from the existing codebase before formal indexing.
<!-- auto-harness:end -->
