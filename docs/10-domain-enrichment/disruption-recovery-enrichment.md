# Disruption Recovery implementation

## Implementation

Disruption Recovery is a Python service with incidents, recovery cases, option selection, manual outcomes and service alerts.
Reports classify disruption type and severity and create cases for the supplied affected order references.
Recovery options include waiting, refunds, compensation, manual handling and scoped connection reaccommodation.
Selected refunds open Post Sales cases and await `PostSalesApplied`; compensation calls Wallet Promotion, and reaccommodation calls Transfer Management.
The domain also calculates compensation amounts, scores supplied alternative routes and groups affected bookings into processing batches.

## Interfaces and sources

[HTTP API](../08-contracts/api/disruption-recovery.md) and [route definitions](../../services/disruption-recovery/src/disruption_recovery/web/handlers.py) cover `/api/v1/disruptions`, incidents, recovery cases, option selection, manual resolution and service alerts.
[Domain](../../services/disruption-recovery/src/disruption_recovery/domain.py), [application service](../../services/disruption-recovery/src/disruption_recovery/application/service.py) and [downstream client](../../services/disruption-recovery/src/disruption_recovery/downstream.py) define calculation and execution paths.
[Service README](../../services/disruption-recovery/README.md) identifies recovery ownership.

## Boundaries

Batch processing uses supplied bookings and alternatives and emits decision and progress events.
Its `PassengerRebooked` and `CompensationIssued` events do not themselves execute ticket changes or money transfers.
Selected recovery options use the downstream calls described above, whose resulting state remains owned by those services.
Batch `slaMinutes` and compensation `issuedBy` fields are recorded policy values and do not establish measured completion times.
Deployment and verification evidence, including differences from workspace code, are recorded in [current status](../00-current-status.md) for Helm revision 279.
Remaining work is maintained only in [TODO](../TODO.md).
