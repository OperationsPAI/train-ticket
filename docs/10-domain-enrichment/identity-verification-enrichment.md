# Identity Verification implementation

## Implementation

Identity Verification is a Python service with credential registration, verification cases, eligibility certificates and purchase limit facts.
The local `verifications` operation checks five document types, document format and expiry, optional name matching, blacklist restrictions and recorded duplicate tickets, with cached verification results.
Verification cases use `DeterministicSimGateway`, which returns matching, rejected or manual review outcomes from supplied material.
Preorder checks validate recorded credentials and the exact quoted eligibility usages, reserve certificate uses and record purchase limit facts.
Confirmation binds the check to a journey order; release updates usage reservations and purchase limit facts.

## Interfaces and sources

[HTTP API](../08-contracts/api/identity-verification.md) and [route definitions](../../services/identity-verification/src/identity_verification/web/handlers.py) describe resources under `/api/v1/identity-verification`.
The route groups include `credentials`, `verifications`, `verification-cases`, `eligibility-certificates`, `pre-order-checks` and `purchase-limit-facts`.
[Domain](../../services/identity-verification/src/identity_verification/domain.py), [application service](../../services/identity-verification/src/identity_verification/application/service.py), [PostgreSQL adapter](../../services/identity-verification/src/identity_verification/adapters/storage/postgres.py) and [subscriber](../../services/identity-verification/src/identity_verification/adapters/messaging/subscriber.py) define validation, persistence and upstream handling.
[Service README](../../services/identity-verification/README.md) describes the service scope.

## Boundaries

The SIM gateway is a local deterministic implementation without an external identity authority call.
The local duplicate ticket check requires both segment and departure date and uses the service's recorded ticket registry.
Preorder purchase limit facts and local duplicate ticket checks are separate paths; their existence does not establish universal duplicate booking prevention.
Fare Pricing calculates discounts, and Journey Order owns order state.
Deployment and verification evidence, including differences from workspace code, are recorded in [current status](../00-current-status.md) for Helm revision 279.
Remaining work is maintained only in [TODO](../TODO.md).
