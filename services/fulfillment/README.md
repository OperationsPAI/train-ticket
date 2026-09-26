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

## Boarding readers

Which readers a boarding fact may arrive under is the estate's own statement,
not a list this service carries. The ticketing authority publishes the estate's
boarding roster and Place & Network publishes the estate's reader register; the
image vendors both at build (`deploy/docker/fulfillment`) into
`FULFILLMENT_READERS_DIR`, and a reader is operated only when both statements
carry it, so a reader the estate has retired is refused even while the roster
still names it. The vocabulary the image was built with is the answer only for a
deployment that ships no roster at all.

## DDD Sources

- `docs/02-domains/fulfillment.md`

## Language Rationale

Go keeps event ingestion and station-side fact normalization operationally simple.

## Skeleton Check

```bash
go test ./...
```
