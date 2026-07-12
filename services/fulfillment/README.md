# fulfillment

Domain: Fulfillment

Language: golang

Phase: phase-1-limited

Status: skeleton

## Owns

- FulfillmentRecord
- BoardingVerified
- NoShow
- EvidenceDispute

## DDD Sources

- `docs/02-domains/fulfillment.md`

## Language Rationale

Go keeps event ingestion and station-side fact normalization operationally simple.

## Skeleton Check

```bash
go test ./...
```
