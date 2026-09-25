# Corporate Travel Events

Last updated: 2026-09-25.

Corporate Travel writes events to its transactional outbox and publishes them on `events:corporate-travel`.

Current event families include agreement creation/activation, employee authorization, travel-policy checks, approval grants or requests, budget reservation/release/commit, billing-period closure and consolidated invoice generation. Payloads preserve the owning agreement, employee/account, booking, budget and billing references used by the command.

Business initialization is not a direct database seed. Creating an initial corporate agreement through `POST /api/v1/agreements` produces the same `CorporateAgreementCreated` and optional `CorporateAgreementActivated` facts as any later agreement. Consumers deduplicate by envelope `eventId`; HTTP retries are independently protected by persisted `(path, Idempotency-Key)` records.
