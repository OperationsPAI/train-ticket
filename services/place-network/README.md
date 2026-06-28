# place-network

Domain: Place & Network

Language: golang

Phase: phase-1-core

Status: skeleton

## Owns

- Place
- TransportNode
- ProviderPlaceMapping

## DDD Sources

- `docs/02-domains/place-network.md`

## Language Rationale

Go fits read-heavy master-data APIs and simple operational lookup paths.

## Skeleton Check

```bash
go test ./...
```
