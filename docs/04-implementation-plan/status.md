## Accepted Work
- Accepted DDD baseline remains the implementation target under `docs/03-ddd-final`.
- Repository status accepted: current source is legacy train-ticket microservice code with no verified DDD work package completion and no `src/test` coverage for required invariants.
- Phase 1 priority accepted: start with WP-01, WP-02, WP-03, and WP-05.

## Rejected Work
- WP-01 brief rejected after 2 attempts.
  - Provider Integration coverage is ambiguous: listed provider/channel facts are external facts and must be mapped before becoming core events.
  - `ts-common` contract example payloads risk becoming shared domain-event payload ownership.
  - Future-scope domains were included as implementation inputs despite Phase 1-only acceptance.
  - EventMetadata UUID representation and validation boundary are underspecified.
- Completion claim rejected for WP-01..WP-23: no assessed work package is complete in current source.

## Blockers
- No approved WP-01 implementation brief exists.
- Provider Integration coverage decision is required: exclude it from the producer matrix and cover through ACL mapping examples, or add a Provider Integration-owned normalized/archived fact that is not consumed as Payment or Booking state until mapped.
- WP-01 must constrain shared-kernel payload examples to test-only non-normative stubs.
- WP-01 must limit acceptance evidence to final Phase 1 domains and keep future-scope domains out of implementation inputs.
- WP-01 must specify UUID storage/validation and JSON behavior for EventMetadata.
- Current source still has direct legacy writes that violate baseline redlines: order payment/cancel/delete/rebook/admin updates and order-derived seat allocation.

## Next Executable Steps
1. Revise WP-01 brief only; do not implement yet.
2. Define the exact Phase 1 envelope coverage matrix and Provider Integration handling.
3. State that contract example payloads are test-only local stubs; real payloads stay in producer bounded contexts.
4. Remove future-scope domains from WP-01 acceptance evidence or mark them non-acceptance references only.
5. Choose EventMetadata UUID representation and validation boundary; require lowercase RFC-4122 JSON output and deserialization/rehydration validation.
6. After WP-01 brief approval, implement the smallest shared-kernel slice with immutable value/reference objects, `DomainEventEnvelope`/`EventMetadata`, and unit tests.
7. Prepare WP-02 brief for Place & Network.
8. Prepare WP-03 brief for Service Plan after WP-02 dependency is explicit.
9. Prepare WP-05 brief for Capacity & Availability after shared refs are available.
