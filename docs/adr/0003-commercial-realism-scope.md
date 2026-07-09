# ADR-0003: Commercial-Realism Scope — P0/P1 Domain Expansion

- Status: accepted
- Date: 2026-07-10
- Decision owner: orchestrator (user-approved 2026-07-10)

## Context

The 29-service system covers the original DDD blueprint (ADR-0002 arc
complete, cert 470/0). A business-scenario gap review against real
ticketing platforms identified P0 gaps (cannot run a real business
without) and P1 gaps (competitive necessity). The user approved adding
all of them as abstractions: external parties (payment channels,
government ID gateway, insurers) are SIMULATED behind ACLs — same
pattern as provider-integration — never real integrations.

## Decision: nine new bounded contexts

| # | Domain | Core ownership | Simulated externals |
|---|---|---|---|
| 1 | payment-channel | Channel orders, channel refund routing (原路退回), channel statement generation, channel adapter ACL | ALIPAY_SIM / WECHAT_SIM / UNIONPAY_SIM |
| 2 | invoicing | Invoice titles (抬头), e-invoice issuance, red-flush (红冲) on refund, itinerary receipts (行程单), 报销导出 | Tax-bureau e-invoice gateway (SIM) |
| 3 | identity-verification | Real-name verification cases, credential registry (student/child/military), eligibility certificates w/ validity, per-identity purchase-limit facts | Government ID gateway (SIM) |
| 4 | seat-assignment | Seat maps per vehicle composition, seat/berth allocation, adjacent-seat grouping, seat holds tied to capacity reservations | none |
| 5 | marketing-campaign | Campaigns, coupon templates, targeting/issuance rules, redemption validation; issues instruments INTO wallet-promotion (issueSource=CAMPAIGN) | none |
| 6 | loyalty-membership | Membership tiers, points accrual (from journey completion facts), tier benefits, points redemption (via wallet issuance) | none |
| 7 | group-booking | Group orders (团体), bulk holds w/ approval flow, staged ticketing, partial fulfilment/decay | none |
| 8 | corporate-travel | Corporate agreements (协议价 refs), employee authorization, monthly billing aggregation (月结 into finance-settlement) | none |
| 9 | travel-insurance | Insurance products, policy issuance attached to orders, claims linked to disruption/post-sales facts | Insurer gateway (SIM) |

## Decision: extensions to existing domains (no new context)

- post-sales + fare-pricing: graded refund-fee schedules (阶梯退票费),
  change-to-different-station, post-departure change windows.
- disruption-recovery + service-plan: schedule-revision (调图) events →
  batch impact scan → mass recovery-case generation.
- capacity-availability + trip-planning + fare-pricing: availability
  calendar and low-price calendar read models.
- risk-compliance: scalper detection (velocity rules, device
  fingerprint abstraction, multi-account correlation, per-identity
  purchase limits fed by identity-verification). Loadgen gains a
  SCALPER actor whose blocked-rate is an assertable metric.
- payment: channel handoff increments (channelRef on intents/captures,
  refund routing via payment-channel).

## Activation order (waves; same discipline as ADR-0002)

1. **Wave A (P0 core objects)**: seat-assignment → identity-verification
   → payment-channel → invoicing. Seat and identity first: they change
   the purchase path that everything else rides.
2. **Wave B (P0 rule depth, extensions)**: post-sales/fare-pricing
   refund-fee schedules; payment channel handoff.
3. **Wave C (P1 commercial)**: marketing-campaign → loyalty-membership
   → travel-insurance.
4. **Wave D (P1 B2B)**: group-booking → corporate-travel.
5. **Wave E (P1 ops & risk)**: schedule-change mass recovery;
   availability/low-price calendars; scalper risk + loadgen scalper.

Rationale: within a wave order is by dependency; across waves each
wave's outputs are the next wave's substrate (marketing needs wallet
(exists) and identity; group-booking needs seat holds; scalper rules
need identity limits and calendar read paths to attack).

## Language assignment (fleet balance)

seat-assignment=Rust, identity-verification=Python, payment-channel=Go,
invoicing=Rust, marketing-campaign=TS, loyalty-membership=TS,
group-booking=Go, corporate-travel=Java, travel-insurance=Java.
Resulting fleet: Java 10, Python 8, TS 7, Go 8, Rust 5 (38 services).

## Consequences

- Nine domain design docs to be authored under docs/02-domains/ using
  the established template, then contracts, then implementation, each
  through the standard gate (verifier + orchestrator live gate,
  single-PR-per-wave).
- Simulated externals live INSIDE the owning context's ACL adapters
  (deterministic, seedable), mirroring provider-integration.
- No real third-party credentials or network calls, ever.
