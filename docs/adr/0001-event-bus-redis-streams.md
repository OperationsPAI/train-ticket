# ADR-0001: Event Bus — Redis Streams

**Status:** Accepted  
**Date:** 2026-07-03  
**Deciders:** Architecture Team  
**Depends on:** REQ-029 (Event Envelope / shared-primitives contract)

---

## Context

Phase 1 of the Train Ticket greenfield rebuild requires an event bus to carry
domain events between bounded contexts. All 28 microservices (22 implemented
contexts + platform modules) must publish and consume events for the three core
business chains (正向购票, 退票, 改签) plus notification/finance/reporting fan-in.

The orchestrator team has already selected **Redis Streams** as the middleware.
This ADR records the decision rationale, alternatives considered, and
architectural consequences — it does NOT re-litigate the choice.

## Decision

Use **Redis Streams** (XADD / XREADGROUP consumer groups) as the integration
event bus for 联调 (integration testing) and phase-1 production deployment.

| Aspect | Choice |
|---|---|
| Data structure | Redis Streams (not Pub/Sub) |
| Consumer model | Consumer groups with XREADGROUP |
| Acknowledgement | XACK after successful local processing |
| Recovery | XAUTOCLAIM with min-idle 60s |
| Poison handling | Dead-letter stream after 5 delivery attempts |
| Retention | MAXLEN ~ 100000 per stream |
| Replay | XRANGE |

### Why NOT Redis Pub/Sub

Redis Pub/Sub is **fire-and-forget**: if a consumer is disconnected or restarts,
all messages published during that window are permanently lost. This is
unacceptable for saga chains where every event (PaymentCaptured,
EntitlementIssued, CapacityReleased) must be delivered at-least-once to
guarantee business process completion.

### Alternatives Considered

| Alternative | Rejection Reason |
|---|---|
| **NATS JetStream** | Excellent at-least-once and exactly-once guarantees, but adds another infrastructure dependency (NATS cluster) beyond the already-required Redis for caching/sessions. For phase-1 联调, keeping the bus on Redis reduces operational surface. |
| **Kafka / Redpanda** | Industry standard for event streaming, but requires a dedicated cluster, topic management tooling, and higher memory footprint. The phase-1 deployment targets local K8s (minikube or single-node); Redis Streams runs inside the same Redis used for other concerns, minimising infrastructure. |
| **RabbitMQ (streams plugin)** | RabbitMQ classic queues lack the consumer-group semantics needed for competing-consumer event distribution. The streams plugin is relatively new and less battle-tested in polyglot microservice environments. |

## Consequences

1. **At-least-once delivery.** Consumers MUST deduplicate by envelope `eventId`
   (already a contract invariant in shared-primitives.md). Duplicate delivery is
   possible after consumer restart or rebalance.

2. **Replay capability.** Use XRANGE to replay historical events for new
   consumers or for recovery after data loss.

3. **Bounded retention.** `MAXLEN ~ 100000` keeps memory usage predictable
   during the integration phase. Retention policy will be revisited for
   production scale-out.

4. **Cross-stream ordering not guaranteed.** Sagas correlate via
   `correlationId` / `causationId` instead of relying on global order.

5. **Consumers own dedup.** Each consumer maintains a consumed-event log
   (persisted by eventId); finance-settlement and reporting already model this,
   and all other contexts follow the same pattern.

6. **Pending-list recovery.** XAUTOCLAIM with 60s min-idle re-assigns messages
   whose consumer crashed before XACK. Poison messages (exceeding 5 delivery
   attempts) are moved to a dedicated dead-letter stream.

7. **No code changes in this ADR.** Implementation tasks will produce the actual
   adapter code per language.
