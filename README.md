# Train Ticket: A New Version

## Introduction

The legacy version of Train Ticket has become outdated and suffered from a lack of maintenance. In response, we've developed a new, more advanced version.

## What's New

- Simplified Development and Deployment: We've streamlined the process, ensuring full integration with Kubernetes (k8s) and Helm for a smoother experience.
- Pruned Redundancies: Certain components that no longer served a purpose have been removed to enhance efficiency.
- Enhanced Monitoring: Comprehensive monitoring capabilities have been integrated, including tools like SkyWalking, OpenTelemetry, Kindling, and DeepFlow, to ensure robust performance insights.

## Development Guide

To get started with development, you'll need to set up a few tools:

1. Install [helm](https://helm.sh/docs/intro/install/) for managing Kubernetes applications.
2. Install [skaffold](https://skaffold.dev/docs/install/) for a streamlined workflow that includes building, pushing, and deploying applications.
3. Install [minikube](https://minikube.sigs.k8s.io/docs/start/) for running Kubernetes locally.

After setting up the necessary tools, you can deploy your application using Skaffold:

```bash
mvn clean package -Dmaven.test.skip=true
skaffold run
skaffold build --default-repo=10.10.10.240/library # push to the local repository
```

## Deployment Instructions

For deployment, the primary requirement is Helm:

1. Ensure Helm is installed.
2. To deploy the application, use the following Helm command:

```bash
helm install ts manifests/helm/generic_service -n ts --create-namespace --set global.monitoring=opentelemtry --set skywalking.enabled=false --set global.image.tag=3384da1c # your image tag

# if use prebuild images:
helm install ts manifests/helm/generic_service -n ts --create-namespace --set global.monitoring=opentelemtry --set skywalking.enabled=false --set global.image.tag=latest --set global.image.repository=registry.cn-shenzhen.aliyuncs.com/lincyaw


# use apo
helm upgrade ts manifests/helm/generic_service -n ts-dev --create-namespace --set global.monitoring=opentelemtry --set opentelemtry.enabled=false --set services.tsUiDashboard.nodePort=30081 --set global.image.tag=310a67e0

# uninstall
helm uninstall ts -n ts
```

Note: If you change the release name, you must also update the values.yaml file accordingly. For instance, when disabling the PostgreSQL component for demo purposes (not recommended for production), ensure you configure the host to match your PostgreSQL service's hostname, as shown below:

```yaml
postgresql:
  enabled: false # To disable the demo PostgreSQL deployment (not for production use).
  config:
    # Specify your PostgreSQL service's hostname (effective when postgresql.enabled is false).
    host: ts-postgresql # Important: Update this to match your service name!
  auth:
```

This new version is designed to offer a more streamlined, efficient, and powerful solution for managing train ticket services, leveraging the latest in technology and best practices.