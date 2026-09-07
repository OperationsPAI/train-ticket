from datetime import UTC, datetime, timedelta
import pytest
from identity_verification.domain import CredentialRecord, DomainError, VerificationCase, SimOutcome, EligibilityCertificate

# Fixtures below that are passed explicitly as `at` into pure domain
# constructors stay pinned on purpose -- the domain receives its clock as an
# argument, so a literal there is deterministic, not a time bomb. Only values
# that the *service* compares against the real `now_utc()` (blacklist
# effectiveFrom, passport expiryDate) must be relative; those use NOW.
NOW = datetime.now(UTC).replace(microsecond=0)


def test_verification_state_transitions_pass_and_manual_override():
    now = datetime(2026, 1, 1, tzinfo=UTC)
    credential = CredentialRecord.register(credential_id="crd-1", traveler_id="tvl-1", profile_snapshot_version="snap-1", document_type="ID_CARD", masked_document_no="11***********0", document_hash="hash0", material_fingerprint="fp", canonical_name_hash="name", birth_date_hash=None, valid_until=now + timedelta(days=1), evidence_hash=None, identity_cluster_id="icl-1", at=now)
    case = VerificationCase.start("ivc-1", "tvl-1", credential.credentialRecordId, "ORDER_CREATION", "fp", "sim-tail-v1", now)
    assert case.version == 1
    passed = case.submit_and_record(SimOutcome.MATCH, "sim-1", now)
    assert passed.status == "PASSED"
    assert passed.version == 3
    manual = VerificationCase.start("ivc-2", "tvl-1", credential.credentialRecordId, "ORDER_CREATION", "fp2", "sim-tail-v1", now).submit_and_record(SimOutcome.MANUAL_REVIEW_REQUIRED, "sim-2", now)
    overridden = manual.manual_override(now)
    assert overridden.status == "PASSED"
    assert overridden.version == 4


def test_invalid_certificate_window_rejected():
    now = datetime(2026, 1, 1, tzinfo=UTC)
    with pytest.raises(DomainError):
        EligibilityCertificate.register(certificate_id="elc-1", traveler_id="tvl-1", credential_id="crd-1", cluster_id=None, eligibility_type="STUDENT", valid_from=now, valid_until=now, policy_year="2026", policy_version="v1", annual_usage_limit=4, product_codes=("TRAIN",), certificate_hash="h", evidence_hash="e", at=now)


def test_certificate_and_fact_versions_advance_monotonically():
    now = datetime(2026, 1, 1, tzinfo=UTC)
    cert = EligibilityCertificate.register(certificate_id="elc-2", traveler_id="tvl-1", credential_id="crd-1", cluster_id=None, eligibility_type="STUDENT", valid_from=now, valid_until=now + timedelta(days=10), policy_year="2026", policy_version="v1", annual_usage_limit=2, product_codes=("TRAIN",), certificate_hash="h2", evidence_hash="e2", at=now)
    cert = cert.reserve(now)
    assert cert.version == 1
    cert = cert.confirm(now)
    assert cert.version == 2
    cert = cert.reserve(now)
    assert cert.version == 3
    cert = cert.release(now)
    assert cert.version == 4


def test_purchase_limit_fact_missed_and_failed_transitions():
    from identity_verification.domain import PurchaseLimitFact
    now = datetime(2026, 1, 1, tzinfo=UTC)
    fact = PurchaseLimitFact("plf-1", "CREDENTIAL", "crd-1", "tvl-1", "oint-1", "2026-02-01", "TRAIN", ("seg-1",), "limit-v1", "RECORDED", now, 1)
    missed = fact.miss("ttl-10m", "mon-1", now)
    assert missed.status == "MISSED"
    assert missed.version == 2
    assert missed.ttlBucket == "ttl-10m"
    failed = fact.fail("LEDGER_CONFLICT", "det-1", now)
    assert failed.status == "FAILED"
    assert failed.version == 2
    assert failed.failureCode == "LEDGER_CONFLICT"


def test_real_name_duplicate_blacklist_expiry_and_cache():
    from identity_verification.application.service import IdentityVerificationService, InMemoryStore
    from identity_verification.domain import ActiveTicket, BlacklistEntry, BlacklistType

    # Blacklist entries are matched with `active_at(now_utc())`, so their
    # effectiveFrom must be in the past relative to the *run*, not to a fixed
    # calendar day. NOW-1d is already-effective on every possible run date.
    blacklisted_from = NOW - timedelta(days=1)
    store = InMemoryStore()
    service = IdentityVerificationService(store)
    doc = "11010519491231002X"
    request = {"travelerId": "tvl-real", "documentType": "ID_CARD", "documentNumber": doc, "holderName": "张三", "segmentRef": "seg-1", "departureDate": "2026-02-01", "seatClass": "SECOND_CLASS", "bookingValueMinor": 10000}
    first = service.verify_identity(request, "corr-real", "cmd-real")
    assert first["status"] == "VERIFIED"
    assert first["duplicateTicketCheck"] == "PASS"
    assert first["cacheHit"] is False
    assert [event.eventType for event in store.take_outbox()] == ["IdentityVerified"]

    cached = service.verify_identity(request, "corr-real", "cmd-real-2")
    assert cached["status"] == "VERIFIED"
    assert cached["cacheHit"] is True
    assert store.take_outbox() == ()

    store.save_active_ticket(ActiveTicket(doc, "seg-1", "2026-02-01", "ord-1"))
    duplicate = service.verify_identity({**request, "travelerId": "tvl-other"}, "corr-real", "cmd-real-3")
    assert duplicate["status"] == "REJECTED"
    assert duplicate["reason"] == "DUPLICATE_TICKET"
    assert duplicate["duplicateTicketCheck"] == "FAIL"

    security_doc = "110105194912310038"
    store.add_blacklist_entry(BlacklistEntry(security_doc, BlacklistType.SECURITY_BAN, "security", blacklisted_from))
    security = service.verify_identity({**request, "documentNumber": security_doc, "travelerId": "tvl-ban", "segmentRef": "seg-ban"}, "corr-real", "cmd-real-4")
    assert security["status"] == "BLACKLISTED"
    assert security["restrictions"] == ["TRAVEL_BAN"]

    credit_doc = "110105194912310046"
    store.add_blacklist_entry(BlacklistEntry(credit_doc, BlacklistType.CREDIT_DEFAULT, "credit", blacklisted_from))
    first_class = service.verify_identity({**request, "documentNumber": credit_doc, "travelerId": "tvl-credit", "segmentRef": "seg-credit", "seatClass": "FIRST_CLASS"}, "corr-real", "cmd-real-5")
    assert first_class["status"] == "BLACKLISTED"
    assert first_class["reason"] == "CREDIT_DEFAULT_RESTRICTED_CLASS"
    rejection_events = [event for event in store.take_outbox() if event.eventType == "IdentityRejected"]
    assert rejection_events[-1].payload["reason"] == "BLACKLISTED"
    second_class = service.verify_identity({**request, "documentNumber": credit_doc, "travelerId": "tvl-credit", "segmentRef": "seg-credit-2", "seatClass": "SECOND_CLASS"}, "corr-real", "cmd-real-6")
    assert second_class["status"] == "VERIFIED"

    # Passport expiry is compared against now_utc(). A literal past year (e.g.
    # "2020-01-01") would keep passing forever without proving the comparison
    # is a real boundary, so drive both sides of it relative to NOW.
    expired = service.verify_identity({"travelerId": "tvl-passport", "documentType": "PASSPORT", "documentNumber": "E12345678", "holderName": "LI SI", "expiryDate": (NOW - timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%SZ")}, "corr-real", "cmd-real-7")
    assert expired["status"] == "EXPIRED"
    assert expired["reason"] == "EXPIRED_DOCUMENT"

    # Counterpart: a passport expiring after NOW must NOT be rejected. Without
    # this, the assertion above would still hold if every document were treated
    # as expired.
    valid_passport = service.verify_identity({"travelerId": "tvl-passport-ok", "documentType": "PASSPORT", "documentNumber": "E87654321", "holderName": "LI SI", "expiryDate": (NOW + timedelta(days=365)).strftime("%Y-%m-%dT%H:%M:%SZ")}, "corr-real", "cmd-real-8")
    assert valid_passport["status"] == "VERIFIED"
    assert valid_passport.get("reason") in (None, "")


def test_journey_order_and_risk_events_maintain_registries():
    from identity_verification.application.service import IdentityVerificationService, InMemoryStore
    from train_ticket_platform.events import EventEnvelope

    store = InMemoryStore()
    service = IdentityVerificationService(store)
    service.verify_identity({"travelerId": "tvl-reg", "documentType": "ID_CARD", "documentNumber": "11010519491231002X", "holderName": "张三"}, "corr-risk", "cmd-verify")
    service.register_credential({"travelerId": "tvl-reg", "profileSnapshotVersion": "snap-reg", "documentType": "ID_CARD", "maskedDocumentNo": "110105********002X", "documentHash": "doc-reg-0", "canonicalNameHash": "name-reg"}, "corr-risk", "cmd-cred")
    credential = store.credentials_for_traveler("tvl-reg")[0]
    store.save_credential(credential.mark_verified("ivc-reg", at=datetime(2026, 1, 1, tzinfo=UTC)))
    service.pre_order_check({"orderIntentId": "oint-reg", "accountId": "acct-reg", "travelerRefs": ["tvl-reg"], "segmentRefs": ["seg-reg"], "journeyDate": "2026-02-01", "productCode": "TRAIN", "requestedEligibilityTypes": [], "limitPolicyVersion": "limit-v1"}, "corr-risk", "cmd-pre")
    service.handle_journey_order_event(EventEnvelope(eventId="evt-journey-created", eventType="JourneyOrderCreated", producer="journey-order", payload={"orderId": "ord-reg", "accountId": "acct-reg", "offerId": "off-reg", "monetarySummary": {"total": {"currency": "CNY", "amount": "100.00"}, "currency": "CNY"}, "segmentRefs": ["seg-reg"], "travelerRefs": [{"travelerId": "tvl-reg", "travelerType": "ADULT", "maskedDocumentNo": "110105********002X"}], "createdAt": "2026-01-01T00:00:00Z"}))
    assert store.find_active_ticket("11010519491231002X", "seg-reg", "2026-02-01") is not None
    service.handle_risk_alert_raised(EventEnvelope(eventId="evt-risk-alert", eventType="RiskAlertRaised", producer="risk-compliance", payload={"evaluationId": "rsk-reg", "orderId": "ord-reg", "accountId": "acct-reg", "score": 80, "verdict": "CHALLENGE", "triggeredRules": [{"ruleId": "velocity"}], "raisedAt": "2026-01-01T00:05:00Z"}))
    service.handle_journey_order_event(EventEnvelope(eventId="evt-journey-cancelled", eventType="JourneyOrderCancelled", producer="journey-order", payload={"orderId": "ord-reg", "accountId": "acct-reg", "reason": "USER_CANCELLED"}))
    assert store.find_active_ticket("11010519491231002X", "seg-reg", "2026-02-01") is None

    result = service.verify_identity({"travelerId": "tvl-risk", "documentType": "ID_CARD", "documentNumber": "11010519491231002X", "holderName": "张三", "seatClass": "SECOND_CLASS"}, "corr-risk", "cmd-risk")
    assert result["status"] == "CHALLENGE"
    assert result["reason"] == "FRAUD_FLAGGED"
    assert result["restrictions"] == ["MANUAL_REVIEW_REQUIRED"]
    rejection_events = [event for event in store.take_outbox() if event.eventType == "IdentityRejected"]
    assert rejection_events[-1].payload["reason"] == "BLACKLISTED"
