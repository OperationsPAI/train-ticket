# Service interfaces

Last updated: 2026-09-24.

The [HTTP API index](api/README.md) and `events/` describe service requests, responses, published events and consumer behavior.
[Shared primitives](shared-primitives.md) define identifiers, timestamps, envelopes and common values.
[Messaging](messaging.md) and [persistence](persistence.md) document delivery and storage boundaries.

## Accuracy and versioning

Each interface document must identify whether its fields are deployed or belong to an unpublished workspace implementation.
Current release and verified results are maintained in [current status](../00-current-status.md).
All incomplete implementation and acceptance work is tracked in [TODO](../TODO.md).
An example payload or an approved design is not proof that every consumer has implemented it.

Clients use each owning service's current API and preserve returned business identities on replay.
Financial wire formats and enum values follow the concrete service definition, including reporting and notification representations.
Unrelated event types may be acknowledged without business effects; recognized events still require valid payloads and idempotent handling.
Consumer replay must preserve monetary, ticket and capacity effects across retries.
