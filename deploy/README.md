# Deployment assets

The Helm chart in [helm/train-ticket](helm/train-ticket) defines business services, PostgreSQL, Redis, telemetry components and the Go load generator.
Service and image inventories come from the chart and [skaffold.yaml](../skaffold.yaml).
Deployment and acceptance evidence is maintained in [current status](../docs/00-current-status.md).
All pending work is maintained in [TODO](../docs/TODO.md).

## Existing resident release

The resident release is `train-ticket` in namespace `train-ticket`.
The revision 279 environment must retain its deployed image references, workload configuration and persistent data.
Do not apply `make deploy`, `make deploy-fast`, their dependent deployment targets, or a bare Helm upgrade to that release.
The Makefile defaults to `IMAGE_TAG=local`; those defaults do not preserve the resident release's individual image references.
The current documentation task performs no build, deployment or new test run.
Existing broad and purchase class results are linked only through [current status](../docs/00-current-status.md).

The chart's own [values.yaml](train-ticket/values.yaml) is the single values file.
It carries the registry, the StorageClass, the central telemetry wiring, the resident exporters and the `loadgen-resident-state` persistent claim.

Skaffold supplies `services.<name>.image` and `loadgen.image` through `setValueTemplates`.
Those complete references take precedence over `global.imageTag` in the chart's image helper.
Its `inputDigest` policy derives tags from each artifact's build inputs.
A future resident update requires verification against the deployed Helm values, manifests and image digests, including every retained image override.
The profile describes the deployment mechanism and does not establish that the current checkout is ready to replace the resident release.

Preserve the following resident settings during any subsequently authorized update:

| Setting | Resident value | Source |
| --- | --- | --- |
| Loadgen data | `/data` on `loadgen-resident-state` | `loadgen.statePersistence` in resident values |
| Deployment strategy | `Recreate` | `templates/loadgen.yaml` |
| Arrival mode | `open-loop` | `loadgen-config.yaml` |
| Base arrival rate | `run.target_rps: 20` journeys per second | `loadgen-config.yaml` |
| Request timeout | `target.request_timeout_seconds: 10` | `loadgen-config.yaml` |
| Arrival period | 600 seconds | `loadgen.diurnalPeriodSeconds`, passed as `LOADGEN_DIURNAL_PERIOD_SECONDS` |
| Outcome recording | File and stdout | `recording` plus `loadgen.recordToStdout` |

The YAML file's `run.arrival.diurnal.period_seconds` is 1800; the resident environment variable supplies the effective 600 second value.
The claim has `helm.sh/resource-policy: keep`; that annotation does not protect data from explicit claim or namespace deletion.

## New test environments

The existing scripts remain available for a new test environment with matching image names, registry access and StorageClasses.
Examples in this section are for that new environment and require verification of its prerequisites before execution.
They are not instructions to update the resident release.

### Separate kind cluster

Requirements are Docker, an already created kind cluster, kubectl, Helm 3, Bash and Python 3 with PyYAML.
The build script produces `train-ticket/<service>:<tag>` images and loads them into the explicitly selected kind cluster.
The chart's default values name the registry images the resident cluster pulls, so a new kind deployment needs a values file that matches the loaded image names.
For example, an operator supplied `deploy/helm/values-test.local.yaml` can contain:

```yaml
global:
  imageRegistry: ""
  imageOrg: train-ticket
  imageRepo: ""
  imageTag: local
  imagePullPolicy: IfNotPresent
  infraRegistry: ""
```

The file is an example to create for the new environment; it is not supplied by this documentation change.
Verify that the new cluster provides a default StorageClass and can pull the infrastructure images before using it.
Use an explicit cluster context and namespace:

```bash
# New, separate kind test environment only; verify its values file first.
make deploy KIND_CLUSTER=train-ticket-test KCTX=kind-train-ticket-test \
  NAMESPACE=train-ticket-test IMAGE_TAG=local \
  HELM_VALUES=deploy/helm/values-test.local.yaml
```

The Makefile executes the following dependent stages:

| Target | Effect |
| --- | --- |
| `deploy-images` | Build the image inventory derived from rendered manifests and load it into kind. |
| `deploy-apply` | Run Helm with the selected values and `global.imageTag=$(IMAGE_TAG)`, waiting for readiness. |
| `deploy-db-bootstrap` | Verify the required databases after Helm's bootstrap hooks. |
| `deploy-roll` | Restart deployments selected from `deploy/docker` when images were rebuilt under the same tag. |
| `deploy-services` | Repair unready deployments built by this repository and wait for all deployments. |
| `deploy-seed` | Execute the reference data scripts. |
| `deploy-check` | Run smoke verification, including a persistent business write. |

`make deploy-fast` runs this chain with image building and the explicit image restart stage skipped.
It still applies Helm values, can replace pods after configuration changes, and runs seeding and smoke verification.
A later stage invokes its prerequisites; it is not an isolated operation.

### Registry based test release

The Skaffold `case` profile deploys a separate namespace using the same single values file.
It requires access to the chosen registry, the configured StorageClasses and `clickhouse.telemetry.svc.cluster.local`.
It turns off the two node-scoped exporters, which only the resident release may run, and uses an emptyDir for loadgen data.

```bash
# New test namespace only; verify context, registry, storage and telemetry first.
skaffold run -p case --kube-context <test-context> \
  -n tt-case-<id> -d <reachable-registry>/<organization>
```

`skaffold build` builds and pushes artifacts without deploying a release.
The separate `push-images` and `deploy-acr` Makefile targets support the registry layout in the production and ACR values files.
Those files have their own storage, database and resource settings and require validation for the chosen new environment.
See [deployment guide](../docs/deployment.md) for their scope and prerequisites.

### Variables

| Variable | Default | Meaning |
| --- | --- | --- |
| `IMAGE_TAG` | `local` | Build tag and the global tag passed by the Makefile's Helm targets. |
| `LOCAL_TAG` | `$(IMAGE_TAG)` | Local source tag used when pushing existing images. |
| `KIND_CLUSTER` | `train-ticket` | Cluster receiving images from the kind build path. |
| `KCTX` | Current kubectl context | Context used by deployment and verification scripts. |
| `NAMESPACE` | `train-ticket` | Target namespace; set it explicitly for new test environments. |
| `HELM_RELEASE` | `train-ticket` | Helm release name. |
| `HELM_CHART` | `deploy/helm/train-ticket` | Chart directory. |
| `HELM_VALUES` | `deploy/helm/train-ticket/values.yaml` | Values used by the deployment path and rendering scripts. |
| `HELM_VALUES_ACR` | `deploy/helm/train-ticket/values.yaml` | Values used by `deploy-acr`. |
| `HELM_TIMEOUT` | `15m` | Helm readiness timeout. |
| `ROLLOUT_TIMEOUT` | `300s` | Deployment rollout timeout. |

`render-manifests.sh` and `verify-databases.sh` accept whitespace separated values paths and expand them into repeated `-f` options.
`deploy-acr-apply` performs the same expansion for `HELM_VALUES_ACR`.
The local `deploy-apply` recipe supplies one `-f $(HELM_VALUES)` expression, so its example uses a single values file.

## Database initialization

[db-bootstrap.yaml](helm/train-ticket/templates/db-bootstrap.yaml) creates one Job per PostgreSQL shard from `postgres.instances.<shard>.databases`.
The hooks are `pre-upgrade,post-install`, with `before-hook-creation`, `backoffLimit: 3` and `activeDeadlineSeconds: 300`.
Each Job waits for PostgreSQL, creates missing databases and grants privileges.
Schema migrations belong to the services.

The initdb ConfigMap runs only when PostgreSQL initializes an empty data directory.
On upgrade, the bootstrap hook can create added databases before dependent services start.
The default configuration uses `postgres-core`; a service's `pgInstance` can select a different shard.
The `postgres` Service is an ExternalName alias for `postgres.defaultInstance` and has no pod of its own.
[verify-databases.sh](verify-databases.sh) reads database references from the rendered service DSNs and checks them against each shard.
Database initialization and readiness checks do not establish business acceptance.

## Seeding and verification

[seed.sh](seed.sh) executes `01-seed.sh`, `07-fare-rules.sh` and `21-identity.sh` for places, schedules, fare rules and identity data.
The scripts create business records and refresh validity windows, and `01-seed.sh` writes `deploy/e2e/.refs.env`.
Seeding pauses loadgen and attempts to restore its previous replica count on exit.
The `make e2e` wrapper also pauses loadgen, and the restart script replaces service pods.
These behaviors interrupt a continuous resident observation window and belong in a separately scheduled test environment.

[smoke.sh](smoke.sh) checks ready Service endpoints, required databases and a place creation that is read back through HTTP and PostgreSQL.
It creates a probe pod and a business record, so it is not a read only status query.
The seed and E2E helpers use `kubectl exec` and need an API connection that supports streaming upgrades.
Their curl pod uses `curlimages/curl:8.10.1`; `SMOKE_IMAGE` overrides the smoke probe image only.

Future test runs must use a stable, identified set of deployed images and retain their business and recorder evidence.
See [loadgen documentation](loadgen-go/README.md) for scheduling, outcome fields and persistent recovery behavior.
Test results and resident observations are recorded only in [current status](../docs/00-current-status.md), with pending work in [TODO](../docs/TODO.md).
