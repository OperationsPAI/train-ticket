# Provider Integration — HTTP API

Last updated: 2026-07-08

## Overview

Provider Integration manages external provider (supplier) communication. It
translates internal commands to provider-specific protocols, handles
idempotency, and normalizes responses. Commands arrive on the event bus —
there are no HTTP command endpoints (see Command Surface below).


Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.
## Command Surface (ruling 2026-07-08, REQ-105)

Provider Integration has **no HTTP command endpoints**. Its command surface
is event-driven only:

- Reserve: consumes `SegmentReservationRequested` from
  `events:booking-orchestration`.
- Cancel: consumes `SegmentBookingCancelled` from
  `events:booking-orchestration` (no-op ack when no provider reservation
  exists for the segment booking).

The former internal HTTP endpoints (`POST /api/v1/internal/
provider-reservations` and `.../{segmentBookingId}/cancel`) were removed:
the audit in `docs/08-contracts/provider-integration-http-endpoint-audit.md`
found no callers anywhere, and a second HTTP write path would bypass the
saga's event-driven bookkeeping. Manual intervention goes through
admin-audit manual actions, not direct provider commands.

The HTTP surface is runtime-only: health, readiness, and metadata.

## Bus-only commands

- `ProviderReservationConfirmed` (published event, not a command)
- `ProviderReservationFailed` (published event, not a command)

## Open Issues

- None.
