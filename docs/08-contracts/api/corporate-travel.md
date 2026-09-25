# Corporate Travel — HTTP API

Last updated: 2026-09-25.

Corporate Travel owns corporate agreements, employee authorization, travel-policy checks, budget reservations and consolidated monthly invoice preparation.

Canonical endpoints are under `/api/v1/agreements`; equivalent `/agreements` aliases remain available. Every POST requires a UUID-v7 `Idempotency-Key`. In PostgreSQL runtime, both business state and successful idempotency responses survive process restart.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/agreements` | Create and optionally activate a corporate agreement. |
| GET | `/api/v1/agreements/{agreementId}` | Read the agreement and its authorized travelers. |
| POST | `/api/v1/agreements/{agreementId}/authorize` | Grant an employee authorization under the agreement. |
| POST | `/api/v1/agreements/{agreementId}/policy-checks` | Evaluate policy and reserve applicable budget pools. |
| POST | `/api/v1/agreements/{agreementId}/monthly-invoices` | Produce a consolidated corporate invoice from supplied statement lines. |

Startup does not insert hidden demo agreements or bypass the domain. Initial business configuration is created through the owning API, emits the normal outbox events and is observable through the same GET endpoint after restart. A repeated POST with the same path, body and idempotency key returns the original response; a changed body under that key returns `IDEMPOTENCY_KEY_REUSED`.
