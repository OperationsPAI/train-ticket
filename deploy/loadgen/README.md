# Load Generator

Actor-based, customer-perspective load for the train-ticket cluster. Two
simulated actor pools:

- **Customers** — use public HTTP APIs only. Every request parameter is
  dynamic: discovered via list/search APIs (places, itineraries, quotes,
  offer totals), freshly created (mock accounts/travelers), or reused from
  the persistent registry (existing DB entities, re-validated with a GET
  before use — the "log in and pick my saved traveler" path).
- **Staff** — the platform/agent side, simulated the same way: staff
  workers process queues for saga reservation driving, ticket issuing,
  manual risk review, and support case handling. Customers enqueue and
  wait, like a real user watching a spinner.

Everything probabilistic is a hyperparameter in `config.yaml`:
`journey_mix` (what customers do), `behavior.*` (new-vs-existing identity,
funnel abandonment, sold-out waitlist fallback, channels, seat types),
`staff.*` (agent concurrency, reaction time, approval rates), `run.*`
(customer concurrency, think times, duration, RNG seed), `bootstrap.*`
(inventory guarantees).

## Run

```bash
deploy/loadgen/run.sh          # build + kind load + apply -k + follow stats
kubectl -n train-ticket apply -k deploy/k8s                 # apply config/deployment only
kubectl -n train-ticket scale deploy/loadgen --replicas=0   # pause
```

Edit `config.yaml`, then run `kubectl -n train-ticket apply -k deploy/k8s` (or rerun `run.sh` when rebuilding the image). Kustomize generates a hashed `loadgen-config-*` ConfigMap from the file, and the Deployment reference changes with the hash so Kubernetes rolls loadgen automatically; no manual ConfigMap creation or `rollout restart` is needed.

## Notes

- Stats print every `run.stats_interval_seconds` as a `[stats]` JSON line:
  journey outcomes, staff actions, per-service HTTP codes and latency
  percentiles.
- The registry (known accounts/travelers/purchases/routes) persists to
  `/data` (emptyDir): container restarts keep it, pod deletion starts the
  "returning customer" pool fresh. Server-side data is unaffected.
- Only the `WEB` channel has published fare rule sets out of the box; add
  channel weights only after publishing rule sets for them.
- Bootstrap is ops-side and idempotent; it guarantees searchable inventory
  (cities/services per date) and records route tuples the customers then
  rediscover through the public search API.
- Sold-out inventory (`NO_AVAILABLE_CAPACITY`) is a business outcome. A
  conservative fraction is converted into waitlist traffic via
  `behavior.p_waitlist_on_no_capacity`; stats expose `waitlist:queued`,
  `waitlist:fulfilled`, `waitlist:expired`, and `waitlist:cancelled`. Add
  more `services_per_date`/dates for more capacity if you want fewer sold-out
  journeys.
- Don't run global-state-mutating e2e scripts (07-fare-rules) while the
  generator is running if you care about clean assertions there.
