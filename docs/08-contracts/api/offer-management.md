# Offer Management — HTTP API

Last updated: 2026-07-05

## Overview

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

Offer Management creates priced offers that combine an itinerary, price
snapshot, availability snapshot, and rule snapshot into a time-limited
commercial offer. Offers do not lock inventory.

## Endpoints

### Quote Offer

**POST** `/api/v1/offers`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | string | yes | Account requesting the offer. |
| `channelId` | string | yes | Sales channel. |
| `itineraryRef` | string | yes | Reference to a Trip Planning itinerary. |
| `travelerRefs` | string[] | yes | Traveler references for pricing. |
| `quoteRequestId` | string | no | Client-generated request correlation. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `offerId` | string | Canonical offer ID (`off-<uuid>`). |
| `offerVersion` | integer | Version of this offer. |
| `total` | object | Total offer price (Money). |
| `expiresAt` | timestamp | Offer validity expiry. |
| `priceGuaranteeLevel` | enum | `FIXED_UNTIL_EXPIRY`, `ESTIMATED_ONLY`, `PROVIDER_FINAL_CONFIRM_REQUIRED` |
| `downstreamReference` | object | Minimal reference for order creation. |
| `itineraryRef` | string | Source itinerary reference. |
| `travelerSetHash` | string | Hash of traveler set. |

**Error codes:** `VALIDATION_FAILED`, `DOMAIN_RULE_VIOLATION`, `UNAVAILABLE`

### Get Offer

**GET** `/api/v1/offers/{offerId}`

**Response (200):** Full offer details.

**Error codes:** `NOT_FOUND`

## Bus-only commands

The following commands are consumed from the event bus only and have no HTTP
endpoint:

- `RefreshOffer` (internal: triggered by upstream snapshot changes)

## Open Issues

- None.
