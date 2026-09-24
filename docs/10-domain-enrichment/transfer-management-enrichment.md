# Transfer Management implementation

## Implementation

Transfer Management is a Python service with transfer plans, connections, connection responsibility records, minimum connection time rules and risk policies.
It registers connections, evaluates connection windows, publishes versioned minimum connection time rules and updates risk from segment status reports and upstream events.
The model supports transfer categories and transport modes, and records missed, recovered and terminal connection states.
Protected missed connections can open Disruption Recovery cases, and the reaccommodation endpoint creates a replacement connection record.
The automatic rebooking path selects from supplied suggestions within a four hour window and records the replacement connection and a zero additional cost.

## Interfaces and sources

[HTTP API](../08-contracts/api/transfer-management.md) and [route definitions](../../services/transfer-management/src/transfer_management/web/handlers.py) cover `/api/v1/transfer-plans`, `connections`, `connection-contracts`, `mct-rules`, `risk-policies` and `segment-status-reports`.
[Domain](../../services/transfer-management/src/transfer_management/domain.py), [application service](../../services/transfer-management/src/transfer_management/application/service.py), [topology adapter](../../services/transfer-management/src/transfer_management/topology.py) and [recovery client](../../services/transfer-management/src/transfer_management/downstream.py) define evaluation and integration.
[Service README](../../services/transfer-management/README.md) identifies domain ownership.

## Boundaries

Reaccommodation and automatic rebooking here update connection records and emit events; these operations contain no ticket purchase or capacity reservation call.
Automatic selection depends on supplied suggestions and does not perform a general alternative inventory search.
`SELF_TRANSFER` missed connections do not receive the protected recovery case behavior.
Deployment and verification evidence, including differences from workspace code, are recorded in [current status](../00-current-status.md).
Remaining work is maintained only in [TODO](../TODO.md).
