# travel-insurance

Domain: Travel Insurance

Language: go

Status: implemented

## Owns

- `Policy` aggregate root: issued against a journey order's ancillary item, with a coverage window, a premium in `Money`, and a status lifecycle.
- `Claim` aggregate root: filed against a policy, then approved or rejected. `DELAY_AUTO` claims are the automatic path, filed from a disruption rather than by a traveller.
- `InsuranceProduct`: the versioned catalogue a policy is issued from. A policy names both the code and the version, so a product that changes later does not change what was sold.

## API

| Method | Path |
|---|---|
| POST | `/api/v1/policies` |
| GET | `/api/v1/policies/{id}` |
| POST | `/api/v1/claims` |
| GET | `/api/v1/claims/{id}` |
| POST | `/api/v1/claims/{id}/approve` |
| POST | `/api/v1/claims/{id}/reject` |

Policy issue is idempotent on the request's idempotency key, because it is called from the purchase funnel where a retry must not sell a second policy.

## Tests

```bash
go test ./...
```
