# go-runtime

Shared Go runtime helpers for Train Ticket services.

This platform module owns the production-shaped HTTP baseline used by Go
services without owning bounded-context state. It provides:

- `/health`, `/live`, `/livez`, `/ready`, `/readyz`, and `/metadata` endpoint registration.
- Request ID and correlation ID propagation/generation middleware.
- HTTP server bootstrap defaults.
- A no-op-by-default observer seam for opt-in tracing or metrics adapters.

```bash
go test ./...
```
