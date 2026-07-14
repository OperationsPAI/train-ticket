# Provider Integration — HTTP API

Last updated: 2026-07-15

## Overview

Provider Integration manages external provider (supplier) communication. It
translates internal commands to provider-specific protocols, handles
idempotency, and normalizes responses. Reservation commands remain event-driven;
segment disruption status can also enter through the provider webhook/simulator
HTTP ingress below so Provider Integration, not downstream consumers, owns the
provider-produced event contract.

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

## Command Surface

Reservation command surface is event-driven only:

- Reserve: consumes `SegmentReservationRequested` from
  `events:booking-orchestration`.
- Cancel: consumes `SegmentBookingCancelled` from
  `events:booking-orchestration` (no-op ack when no provider reservation
  exists for the segment booking).

The former internal HTTP endpoints (`POST /api/v1/internal/
provider-reservations` and `.../{segmentBookingId}/cancel`) remain removed:
the audit in `docs/08-contracts/provider-integration-http-endpoint-audit.md`
found no callers anywhere, and a second HTTP write path would bypass the
saga's event-driven bookkeeping. Manual intervention goes through
admin-audit manual actions, not direct provider reservation commands.

## Segment status ingress

`POST /api/v1/provider-segment-status`

Provider webhook adapters and the operations provider simulator use this endpoint
to report provider-sourced operating segment disruptions. The endpoint requires
an `Idempotency-Key` UUID-v7 header and publishes the normative
`ProviderSegmentDelayed` or `ProviderSegmentCancelled` event on
`events:provider-integration`.

### Request body

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentRef` | `SegmentRef` | yes | Affected service segment (`seg-<uuid>`). |
| `scheduledServiceRef` | string | yes | Scheduled service/run reference. |
| `serviceDate` | string | yes | Operating date, `YYYY-MM-DD`. |
| `status` | enum | yes | `DELAY` or `CANCELLED`. |
| `estimatedArrivalAt` | RFC3339 UTC | yes when `status=DELAY` | Latest estimated arrival. |
| `cancelledAt` | RFC3339 UTC | yes when `status=CANCELLED` | Cancellation timestamp. |
| `observedAt` | RFC3339 UTC | yes | When the provider status was observed. |
| `sourceSystem` | string | no | Provider declaration source; defaults to `PROVIDER`. |

### Response `201 Created`

| Field | Type | Description |
|---|---|---|
| `segmentRef` | `SegmentRef` | Accepted segment. |
| `status` | enum | `DELAY` or `CANCELLED`. |
| `observedAt` | RFC3339 UTC | Accepted observation time. |
| `publishedAs` | string | `ProviderSegmentDelayed` or `ProviderSegmentCancelled`. |

## Bus-only events

- `ProviderReservationConfirmed` (published event, not a command)
- `ProviderReservationFailed` (published event, not a command)
- `ProviderSegmentDelayed` (published event from segment status ingress)
- `ProviderSegmentCancelled` (published event from segment status ingress)

## Open Issues

- None.
