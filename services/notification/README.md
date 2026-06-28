# notification

Domain: Notification

Language: typescript

Phase: phase-1-support

Status: skeleton

## Owns

- NotificationTask
- Template
- RecipientPolicy
- DeliveryReceipt

## DDD Sources

- `docs/02-domains/notification.md`

## Language Rationale

TypeScript fits template payloads, channel adapters, and product-facing notification policies.

## Skeleton Check

```bash
npm test
```
