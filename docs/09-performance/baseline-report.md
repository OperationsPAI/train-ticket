# Baseline Performance Report

**Date**: 2026-07-10
**Cluster**: kind-arl-test (single-node, 40 pods)
**Services**: 33 business + infra

## Test Environment

- **Kind cluster**: 1 control-plane node, shared resources
- **Database**: Single PostgreSQL instance (shared across services)
- **Redis**: Single Redis instance (streams + cache)
- **Pod resources**: Unbounded (no resource limits in dev)

## S1 Smoke Test (5 buyers, unlimited seats)

| Metric | Value |
|--------|-------|
| Workers | 5 |
| Duration | 90s |
| Dispatched | 41 |
| Purchased | 37 (90%) |
| Errors | 4 (reservation/ticketing timeout) |
| Effective RPS | 0.33 |

## S1 Contention Test (20 buyers, same segment)

| Metric | Value |
|--------|-------|
| Workers | 20 |
| Duration | 120s |
| Dispatched | 144 |
| Purchased | 65 (45%) |
| Offer failures | 31 (quote propagation race) |
| Reservation timeout | 36 (staff bottleneck) |
| Ticketing timeout | 12 (staff bottleneck) |
| Effective RPS | 0.93 |
| Zero 5xx errors | Yes |
| Zero service crashes | Yes |

## Latency Profile (purchase chain)

| Service | p50 | p95 | p99 | Note |
|---------|-----|-----|-----|------|
| account | 13ms | 19ms | 20ms | Registration |
| traveler-profile | 8ms | 9ms | 11ms | |
| identity-verification | 16ms | 22ms | 24ms | Credential + case |
| trip-planning | 12ms | 18ms | 21ms | Search |
| fare-pricing | 18ms | 19ms | 26ms | Quote |
| offer-management | 8ms | 46ms | 90ms | High tail under contention |
| journey-order | 3ms | 25ms | 78ms | Order creation |
| booking-orchestration | 3ms | 11ms | 17ms | Saga poll |
| payment | 19ms | 22ms | 31ms | Intent + capture |
| entitlement-ticketing | 8ms | 18ms | 18ms | |

## Bottleneck Analysis

### Confirmed Bottlenecks

1. **Staff-driven reservation/ticketing** (Primary)
   - The purchase chain requires staff workers to drive booking-orchestration reservations and entitlement issuance
   - Each staff operation takes 3-10s (saga poll + API call + think time)
   - With N purchase workers and M staff workers, throughput ≈ M / (avg_staff_time)
   - **Impact**: Limits effective RPS to ~0.3-1.0 depending on staff count

2. **Quote → Offer propagation race** (Secondary)
   - fare-pricing publishes FareQuoteCreated to Redis stream
   - offer-management consumes it to build internal projection
   - Under concurrency, the 2s wait is sometimes insufficient
   - **Impact**: 21% of attempts fail at offer stage under 20 concurrent workers

### Not Yet Tested (Pending S2 staircase)

3. Capacity single-aggregate row lock
4. OCC retry amplification
5. Outbox relay polling latency
6. Seat adjacency solver lock duration

## Scalper Behavior Validation

| Metric | Value |
|--------|-------|
| Scalper attempts | 4 |
| Scalper success | 2 (50%) |
| Scalper blocked by risk | 0 |
| Scalper capacity exhausted | 0 |
| Zero errors in scalper path | Yes |

Scalper actors successfully purchase tickets alongside regular customers with zero think time. No risk blocks observed (risk-compliance not yet tuned for scalper detection).

## Recommendations

1. **Short-term**: Increase offer-management event consumption speed or add idempotent retry on offer 422
2. **Medium-term**: Consider auto-reservation (saga-driven) to eliminate staff bottleneck from the purchase critical path
3. **Long-term**: Capacity advisory locks instead of row-level FOR UPDATE for horizontal scaling

## Correctness

- Zero 5xx errors across all tests
- Zero service crashes during 20-concurrent-worker contention
- Redis subscriber reconnection fix verified (all Rust services maintain subscription after Redis restart)
- Payment channel handoff working end-to-end (capture → channel order → refund → channel refund)
