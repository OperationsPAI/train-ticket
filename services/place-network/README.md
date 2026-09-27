# place-network

Domain: Place & Network

Language: golang

Phase: phase-1-core

Status: REQ-004 master-data primitives

## Owns

- Place
- TransportNode
- ProviderPlaceMapping
- Reader register: the readers the estate has in service
  (`config/reader-register.txt`), which Fulfillment vendors at build

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

## Reader register

`config/reader-register.txt` is this service's statement of the readers the
estate has in service, one reader per line with blank lines and comments
ignored. Fulfillment vendors the file into its image at build beside the
boarding roster the ticketing authority publishes, and a boarding fact is
measured against the readers both statements carry, so retiring a reader here is
how an estate stops operating it everywhere.

## Skeleton Check

```bash
go test ./...
```
