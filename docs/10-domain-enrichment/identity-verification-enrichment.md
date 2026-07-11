# Identity Verification Enrichment — Real-Name Enforcement

## Context

**Service**: identity-verification (Python, `services/identity-verification/`)
**Current state**: 288-line domain, stub verification that always passes. No document type support, no blacklist, no duplicate-ticket constraint.

## Requirements

### R1: Multi-Document Type Support

```
Supported document types:
  ID_CARD:           18-digit Chinese ID card (二代身份证)
  PASSPORT:          International passport
  HK_MACAU_PERMIT:   港澳通行证
  TW_PERMIT:         台湾通行证
  RESIDENCE_PERMIT:  外国人永久居留证

Each type has:
  - format validation regex
  - name matching rules (ID card: exact; passport: fuzzy)
  - expiry check (passport/permits have validity dates)
```

**Domain model**:
- `DocumentType` enum with validation regex per type
- `VerificationRequest`: `travelerId`, `documentType`, `documentNumber`, `holderName`, `expiryDate`
- `VerificationResult`: `status` (VERIFIED, REJECTED, EXPIRED, BLACKLISTED), `reason`, `verifiedAt`, `expiresAt`

### R2: Same-Train Duplicate Ticket Constraint

12306 core rule: one person (by document number) can only hold ONE valid ticket per train per date.

```
Constraint: same documentNumber + same segmentRef + same departureDate → REJECT
Exception: previous ticket was cancelled/refunded
Check timing: at identity verification request (before booking)
```

**Domain model**:
- `ActiveTicketRegistry`: tracks (documentNumber, segmentRef, departureDate) → orderId
- `checkDuplicateTicket(documentNumber, segmentRef, departureDate) → boolean`
- Consume `JourneyOrderCreated` / `JourneyOrderCancelled` events to maintain registry

### R3: Blacklist Checking

```
Blacklist types:
  CREDIT_DEFAULT:   失信被执行人 (cannot buy certain classes)
  SECURITY_BAN:     安全限制 (complete travel ban)
  FRAUD_FLAGGED:    风控标记 (requires manual review)

Blacklist check:
  - SECURITY_BAN → REJECT all bookings
  - CREDIT_DEFAULT → allow SECOND_CLASS only, reject FIRST/BUSINESS
  - FRAUD_FLAGGED → CHALLENGE, require additional verification
```

**Domain model**:
- `BlacklistEntry`: `documentNumber`, `blacklistType`, `reason`, `effectiveFrom`, `effectiveUntil`
- `BlacklistChecker`: lookup by documentNumber, return applicable restrictions
- Seeded with simulated blacklist entries for stress testing

### R4: Verification Expiry and Re-verification

```
Verification validity:
  ID_CARD: valid for 30 days (cached)
  PASSPORT: valid for 7 days
  Others: valid for 14 days

Re-verification required when:
  - Previous verification expired
  - Document number changed
  - Booking value > 5000 CNY (high-value re-check)
```

**Domain model**:
- `VerificationCache`: (travelerId, documentNumber) → (verifiedAt, expiresAt)
- `needsReverification(travelerId, documentNumber, bookingValue) → boolean`

## Interface Contracts

### Events consumed
- `events:journey-order` → `JourneyOrderCreated`, `JourneyOrderCancelled` — maintain active ticket registry
- `events:risk-compliance` → `RiskAlertRaised` — add to blacklist on fraud detection

### Events produced
- `IdentityVerified` — travelerId, documentType, verifiedAt
- `IdentityRejected` — travelerId, reason (DUPLICATE_TICKET, BLACKLISTED, EXPIRED_DOCUMENT)
- `BlacklistHit` — documentNumber, blacklistType

### API changes
- `POST /api/v1/verifications` request adds: `segmentRef`, `departureDate`, `seatClass`, `bookingValueMinor`
- Response adds: `restrictions: string[]`, `duplicateTicketCheck: PASS|FAIL`

## Test Criteria

1. Same person, same train, same date → REJECT with DUPLICATE_TICKET
2. Blacklisted (SECURITY_BAN) person → REJECT regardless of class
3. Credit default person booking first class → REJECT; second class → PASS
4. Expired passport → REJECT with EXPIRED_DOCUMENT
5. Verification cache hit within validity → skip re-verification

## Files to Modify

- `src/identity_verification/domain.py` — DocumentType, BlacklistChecker, ActiveTicketRegistry
- `src/identity_verification/application/service.py` — wire constraints
- `src/identity_verification/web/handlers.py` — accept new request fields
- `src/identity_verification/adapters/storage/postgres.py` — blacklist + registry tables
- `migrations/002_identity_enrichment.sql`
- `tests/test_domain.py`
