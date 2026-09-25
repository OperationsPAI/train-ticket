# corporate-travel

Domain: Corporate Travel
Language: Python
Status: REQ-215 service foundation

Corporate Travel owns corporate agreements, authorized traveler lists, corporate billing accounts, and monthly settlement periods. This slice supports agreement creation/activation, traveler authorization, order/payment fact aggregation, and billing-period closure events.

When `DATABASE_URL` is configured, startup applies migrations and wires the owning `/api/v1/agreements` API to PostgreSQL. Agreement, authorization, policy-check budget, billing and outbox state survive process restarts. POST idempotency responses use the same PostgreSQL database, so replaying a command with the same UUID-v7 key after restart returns the original result instead of creating another agreement or authorization.

## Local validation

```bash
python -m pytest
uvicorn corporate_travel.api:create_app --factory --host 127.0.0.1 --port 8080
curl http://127.0.0.1:8080/health
```
