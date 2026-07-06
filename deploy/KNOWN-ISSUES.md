# Known issues for local kind integration

No service source changes were made for this environment. The manifests assume each
integration-ready service starts its existing HTTP process on `PORT=8080` (or
`SERVER_PORT=8080` for Spring Boot) and probes the contract endpoints `/healthz`
and `/readyz`.

Items to verify during the first full kind smoke run:

- The following Java services currently expose `/health`, `/live`, `/livez`,
  `/ready`, `/readyz`, and `/metadata`, but not `/healthz`: `journey-order`,
  `payment`, `post-sales`, `traveler-profile`, `admin-audit`, and
  `finance-settlement`. Their Pods may fail the Kubernetes liveness probe until
  the service runtime adds the contract `/healthz` endpoint. `booking-orchestration`
  already exposes `/healthz`.
- Python service images install `uvicorn[standard]` at image build time because the
  service runtime entrypoints expose FastAPI applications but do not declare an
  HTTP server dependency directly.
- Java images install `platform/java-kit` into the Maven build stage before building
  each service, because services depend on the local platform kit artifact.
- TypeScript images compile service sources to `dist/` and start `bootstrap()` with
  Node; no service package currently declares a production `start` script.
