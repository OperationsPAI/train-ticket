# place-network

Domain: Place & Network

Language: golang

Phase: phase-1-core

Status: REQ-004 master-data primitives

## Owns

- Place
- TransportNode
- ProviderPlaceMapping

## DDD Sources

- `docs/02-domains/place-network.md`

## Language Rationale

Go fits read-heavy master-data APIs and simple operational lookup paths.

## Active Slice

REQ-004 adds small domain primitives for `Place`, `TransportNode`, and
`ProviderPlaceMapping`. The primitives validate canonical names, lifecycle
statuses, WGS84 coordinates, serving transport modes, provider-code confidence,
and explicit low-confidence/conflict gap reasons so provider raw codes do not
leak into downstream core contracts.

## Skeleton Check

```bash
go test ./...
```
