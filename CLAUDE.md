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
- `ts-gateway-service`: API Gateway (Spring Cloud Gateway)
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
- The gateway service (`ts-gateway-service`) routes external requests to internal services
- Services use Spring Boot 3.x with Jakarta EE (not javax)
- Lombok is used extensively for reducing boilerplate code
- The UI dashboard (`ts-ui-dashboard`) is the frontend application
