from datetime import UTC, datetime, timedelta

from fastapi.testclient import TestClient
from identity_verification import create_app
from identity_verification.application.service import InMemoryStore

# --- Calendar-independent fixtures -------------------------------------------
# The service resolves credential validity against the real wall clock
# (`now_utc()` -> `datetime.now(UTC)`; see IdentityVerificationService.
# pre_order_check, which keeps only credentials with `validUntil > at`).
# Absolute literals here would silently expire and detonate the suite on a
# fixed calendar day, exactly as the pinned fare-pricing fixtures did.
#
# NOW is resolved ONCE at import and truncated to whole seconds so that every
# request in a run sees byte-identical timestamps: the credential `validUntil`
# feeds the material fingerprint used for duplicate/idempotency detection, so a
# per-call `now()` would break replay semantics.
NOW = datetime.now(UTC).replace(microsecond=0)


def _iso(moment: datetime) -> str:
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


# A national ID card is a long-lived document. Five years is far longer than any
# CI run or long-lived dev cluster, and matches how real ID validity is issued.
CREDENTIAL_VALID_UNTIL = _iso(NOW + timedelta(days=365 * 5))
REQUESTED_AT = _iso(NOW)
# A realistic advance-purchase travel date. It must land strictly inside the
# certificate window below, never on a boundary, so a midnight rollover between
# fixture construction and assertion cannot flip the window check.
JOURNEY_DATE = (NOW + timedelta(days=30)).date().isoformat()
# Student eligibility certificates run for a policy year. Anchoring the window
# to NOW keeps JOURNEY_DATE (NOW+30d) comfortably inside it on any run date.
CERT_VALID_FROM = _iso(NOW - timedelta(days=30))
CERT_VALID_UNTIL = _iso(NOW + timedelta(days=335))
CERT_POLICY_YEAR = (NOW + timedelta(days=30)).strftime("%Y")


def key(n: int) -> str:
    return f"0194f2e0-7b3e-7610-8000-{n:012d}"


def register(client: TestClient, traveler="tvl-1", tail="5", idem=1):
    body = {"travelerId": traveler, "profileSnapshotVersion": "snap-1", "documentType": "ID_CARD", "maskedDocumentNo": "11***********" + tail, "documentHash": "doc-hash-" + tail, "canonicalNameHash": "name-hash", "validUntil": CREDENTIAL_VALID_UNTIL}
    return client.post("/api/v1/identity-verification/credentials", json=body, headers={"Idempotency-Key": key(idem)})


def verify(client: TestClient, credential, idem=2):
    body = {"travelerId": credential["travelerId"], "credentialRecordId": credential["credentialRecordId"], "purpose": "ORDER_CREATION", "materialFingerprint": credential.get("materialFingerprint", ""), "simPolicyVersion": "sim-tail-v1", "requestedAt": REQUESTED_AT}
    # API response intentionally omits materialFingerprint; store has it.
    stored = client.app.state.identity_verification_store.credentials[credential["credentialRecordId"]]
    body["materialFingerprint"] = stored.materialFingerprint
    return client.post("/api/v1/identity-verification/verification-cases", json=body, headers={"Idempotency-Key": key(idem)})


def test_pass_reject_manual_and_preorder():
    store = InMemoryStore(); client = TestClient(create_app(store=store))
    passed_cred = register(client, tail="5", idem=1).json()
    passed_case = verify(client, passed_cred, 2).json()
    assert passed_case["status"] == "PASSED"

    rejected_cred = register(client, traveler="tvl-2", tail="7", idem=3).json()
    assert verify(client, rejected_cred, 4).json()["status"] == "FAILED"

    manual_cred = register(client, traveler="tvl-3", tail="9", idem=5).json()
    manual_case = verify(client, manual_cred, 6).json()
    assert manual_case["status"] == "MANUAL_REVIEW_REQUIRED"
    override = client.post(f"/api/v1/identity-verification/verification-cases/{manual_case['verificationCaseId']}/manual-override", headers={"Idempotency-Key": key(7)}).json()
    assert override["status"] == "PASSED"

    rejected = client.post("/api/v1/identity-verification/pre-order-checks", json={"orderIntentId": "oint-1", "accountId": "acct-1", "offerId": "off-1", "offerVersion": 1, "travelerRefs": ["missing"], "segmentRefs": ["seg-1"], "journeyDate": JOURNEY_DATE, "productCode": "TRAIN", "limitPolicyVersion": "limit-v1", "requestedAt": REQUESTED_AT}, headers={"Idempotency-Key": key(8)})
    assert rejected.json()["result"] == "REJECT"
    ok = client.post("/api/v1/identity-verification/pre-order-checks", json={"orderIntentId": "oint-2", "accountId": "acct-1", "offerId": "off-1", "offerVersion": 1, "travelerRefs": ["tvl-1"], "segmentRefs": ["seg-1"], "journeyDate": JOURNEY_DATE, "productCode": "TRAIN", "limitPolicyVersion": "limit-v1", "requestedAt": REQUESTED_AT}, headers={"Idempotency-Key": key(9)})
    assert ok.status_code == 201
    assert ok.json()["result"] == "PASS"
    assert store.take_outbox()


def test_idempotency_replay_and_certificate_query():
    client = TestClient(create_app(store=InMemoryStore()))
    first = register(client, idem=10)
    replay = register(client, idem=10)
    assert replay.status_code == 201
    cred = first.json(); verify(client, cred, 11)
    cert = client.post("/api/v1/identity-verification/eligibility-certificates", json={"travelerId": "tvl-1", "credentialRecordId": cred["credentialRecordId"], "eligibilityType": "STUDENT", "validFrom": CERT_VALID_FROM, "validUntil": CERT_VALID_UNTIL, "policyYear": CERT_POLICY_YEAR, "policyVersion": "student-v1", "annualUsageLimit": 4, "applicableProductCodes": ["TRAIN"], "certificateHash": "cert-hash", "evidenceHash": "evidence-hash"}, headers={"Idempotency-Key": key(12)})
    assert cert.status_code == 201
    query = client.get(f"/api/v1/identity-verification/eligibility-certificates?travelerId=tvl-1&eligibilityType=STUDENT&journeyDate={JOURNEY_DATE}&productCode=TRAIN")
    assert query.json()["total"] == 1
    assert "evidenceHash" not in query.json()["items"][0]



def test_preorder_reserve_confirm_release_and_stable_id():
    store = InMemoryStore(); client = TestClient(create_app(store=store))
    cred = register(client, idem=20).json(); verify(client, cred, 21)
    cert = client.post("/api/v1/identity-verification/eligibility-certificates", json={"travelerId": "tvl-1", "credentialRecordId": cred["credentialRecordId"], "eligibilityType": "STUDENT", "validFrom": CERT_VALID_FROM, "validUntil": CERT_VALID_UNTIL, "policyYear": CERT_POLICY_YEAR, "policyVersion": "student-v1", "annualUsageLimit": 2, "applicableProductCodes": ["TRAIN"], "certificateHash": "cert-hash-2", "evidenceHash": "evidence-hash"}, headers={"Idempotency-Key": key(22)}).json()
    body = {"orderIntentId": "oint-stable", "accountId": "acct-1", "offerId": "off-1", "offerVersion": 1, "travelerRefs": ["tvl-1"], "segmentRefs": ["seg-1"], "journeyDate": JOURNEY_DATE, "productCode": "TRAIN", "requestedEligibilityTypes": ["STUDENT"], "limitPolicyVersion": "limit-v1", "requestedAt": REQUESTED_AT}
    first = client.post("/api/v1/identity-verification/pre-order-checks", json=body, headers={"Idempotency-Key": key(23)})
    replay = client.post("/api/v1/identity-verification/pre-order-checks", json=body, headers={"Idempotency-Key": key(24)})
    assert first.json()["preOrderCheckId"] == replay.json()["preOrderCheckId"]
    assert store.certificates[cert["eligibilityCertificateId"]].annualUsageReserved == 1
    events = [event.eventType for event in store.take_outbox()]
    assert "EligibilityUsageReserved" in events
    assert "PurchaseLimitFactRecorded" in events
    client.post(f"/api/v1/identity-verification/pre-order-checks/{first.json()['preOrderCheckId']}/confirm", json={"journeyOrderId": "ord-1"}, headers={"Idempotency-Key": key(25)})
    assert store.certificates[cert["eligibilityCertificateId"]].annualUsageReserved == 0
    assert store.certificates[cert["eligibilityCertificateId"]].annualUsageConfirmed == 1
    assert {event.eventType for event in store.take_outbox()} >= {"EligibilityUsageConfirmed", "PurchaseLimitFactConfirmed"}

    body["orderIntentId"] = "oint-release"
    released = client.post("/api/v1/identity-verification/pre-order-checks", json=body, headers={"Idempotency-Key": key(26)}).json()
    client.post(f"/api/v1/identity-verification/pre-order-checks/{released['preOrderCheckId']}/release", json={"releaseReason": "ORDER_CANCELLED"}, headers={"Idempotency-Key": key(27)})
    assert {event.eventType for event in store.take_outbox()} >= {"EligibilityUsageReleased", "PurchaseLimitFactReleased"}


def test_purchase_limit_missed_and_failed_events():
    store = InMemoryStore(); client = TestClient(create_app(store=store))
    cred = register(client, idem=50).json(); verify(client, cred, 51)
    body = {"orderIntentId": "oint-monitor", "accountId": "acct-1", "offerId": "off-1", "offerVersion": 1, "travelerRefs": ["tvl-1"], "segmentRefs": ["seg-1"], "journeyDate": JOURNEY_DATE, "productCode": "TRAIN", "limitPolicyVersion": "limit-v1", "requestedAt": REQUESTED_AT}
    check = client.post("/api/v1/identity-verification/pre-order-checks", json=body, headers={"Idempotency-Key": key(52)}).json()
    fact_id = check["purchaseLimitFacts"][0]["purchaseLimitFactId"]
    store.take_outbox()
    missed = client.post(f"/api/v1/identity-verification/purchase-limit-facts/{fact_id}/mark-missed", json={"ttlBucket": "ttl-10m", "monitorRunId": "mon-1"}, headers={"Idempotency-Key": key(53)})
    assert missed.status_code == 200
    events = store.take_outbox()
    assert events[0].eventType == "PurchaseLimitFactMissed"
    assert events[0].payload["ttlBucket"] == "ttl-10m"

    body["orderIntentId"] = "oint-failed"
    failed_check = client.post("/api/v1/identity-verification/pre-order-checks", json=body, headers={"Idempotency-Key": key(54)}).json()
    failed_fact_id = failed_check["purchaseLimitFacts"][0]["purchaseLimitFactId"]
    store.take_outbox()
    failed = client.post(f"/api/v1/identity-verification/purchase-limit-facts/{failed_fact_id}/mark-failed", json={"failureCode": "DOWNSTREAM_FAILURE", "detectionRunId": "det-1"}, headers={"Idempotency-Key": key(55)})
    assert failed.status_code == 200
    events = store.take_outbox()
    assert events[0].eventType == "PurchaseLimitFactFailed"
    assert events[0].payload["failureCode"] == "DOWNSTREAM_FAILURE"


def test_certificate_query_excludes_exhausted_certificates():
    store = InMemoryStore(); client = TestClient(create_app(store=store))
    cred = register(client, idem=40).json(); verify(client, cred, 41)
    cert = client.post("/api/v1/identity-verification/eligibility-certificates", json={"travelerId": "tvl-1", "credentialRecordId": cred["credentialRecordId"], "eligibilityType": "STUDENT", "validFrom": CERT_VALID_FROM, "validUntil": CERT_VALID_UNTIL, "policyYear": CERT_POLICY_YEAR, "policyVersion": "student-v1", "annualUsageLimit": 1, "applicableProductCodes": ["TRAIN"], "certificateHash": "cert-hash-exhausted", "evidenceHash": "evidence-hash"}, headers={"Idempotency-Key": key(42)}).json()
    body = {"orderIntentId": "oint-exhaust", "accountId": "acct-1", "offerId": "off-1", "offerVersion": 1, "travelerRefs": ["tvl-1"], "segmentRefs": ["seg-1"], "journeyDate": JOURNEY_DATE, "productCode": "TRAIN", "requestedEligibilityTypes": ["STUDENT"], "limitPolicyVersion": "limit-v1", "requestedAt": REQUESTED_AT}
    first = client.post("/api/v1/identity-verification/pre-order-checks", json=body, headers={"Idempotency-Key": key(43)}).json()
    assert store.certificates[cert["eligibilityCertificateId"]].annualUsageReserved == 1
    query_reserved = client.get(f"/api/v1/identity-verification/eligibility-certificates?travelerId=tvl-1&eligibilityType=STUDENT&journeyDate={JOURNEY_DATE}&productCode=TRAIN")
    assert query_reserved.json()["total"] == 0
    client.post(f"/api/v1/identity-verification/pre-order-checks/{first['preOrderCheckId']}/confirm", json={"journeyOrderId": "ord-exhaust"}, headers={"Idempotency-Key": key(44)})
    query_confirmed = client.get(f"/api/v1/identity-verification/eligibility-certificates?travelerId=tvl-1&eligibilityType=STUDENT&journeyDate={JOURNEY_DATE}&productCode=TRAIN")
    assert query_confirmed.json()["total"] == 0


class StoreWithoutCasesAttribute(InMemoryStore):
    @property
    def cases(self):
        raise AssertionError("service must use find_case_by_credential")

    @cases.setter
    def cases(self, value):
        self._cases = value

    def get_case(self, case_id):
        return self._cases[case_id]

    def save_case(self, case):
        self._cases[case.verificationCaseId] = case
        self.case_duplicate_index[(case.travelerId, case.credentialRecordId, case.purpose, case.materialFingerprint, case.simPolicyVersion)] = case.verificationCaseId

    def find_case_duplicate(self, traveler_id, credential_id, purpose, material_fingerprint, policy):
        cid = self.case_duplicate_index.get((traveler_id, credential_id, purpose, material_fingerprint, policy))
        return self._cases.get(cid) if cid else None

    def find_case_by_credential(self, credential_id):
        cases = [case for case in self._cases.values() if case.credentialRecordId == credential_id]
        return max(cases, key=lambda item: item.createdAt, default=None)


def test_credential_status_uses_store_interface_not_cases_attribute():
    store = StoreWithoutCasesAttribute(); client = TestClient(create_app(store=store))
    cred = register(client, idem=30).json(); verify(client, cred, 31)
    response = client.get(f"/api/v1/identity-verification/credentials/{cred['credentialRecordId']}/verification-status")
    assert response.status_code == 200
    assert response.json()["verificationStatus"] == "PASSED"


def test_real_name_verification_api_contract_and_events():
    store = InMemoryStore(); client = TestClient(create_app(store=store))
    response = client.post("/api/v1/identity-verification/verifications", json={"travelerId": "tvl-api", "documentType": "ID_CARD", "documentNumber": "11010519491231002X", "holderName": "张三", "segmentRef": "seg-api", "departureDate": JOURNEY_DATE, "seatClass": "SECOND_CLASS", "bookingValueMinor": 10000}, headers={"Idempotency-Key": key(90)})
    assert response.status_code == 201
    body = response.json()
    assert body["status"] == "VERIFIED"
    assert body["restrictions"] == []
    assert body["duplicateTicketCheck"] == "PASS"
    assert store.take_outbox()[0].eventType == "IdentityVerified"
    compat = client.post("/api/v1/verifications", json={"travelerId": "tvl-api-compat", "documentType": "ID_CARD", "documentNumber": "110105199001010010", "holderName": "张三", "segmentRef": "seg-api-compat", "departureDate": JOURNEY_DATE, "seatClass": "SECOND_CLASS", "bookingValueMinor": 10000}, headers={"Idempotency-Key": key(92)})
    assert compat.status_code == 201
    assert compat.json()["status"] == "VERIFIED"

    store.save_active_ticket(__import__("identity_verification.domain", fromlist=["ActiveTicket"]).ActiveTicket("11010519491231002X", "seg-api", JOURNEY_DATE, "ord-api"))
    rejected = client.post("/api/v1/identity-verification/verifications", json={"travelerId": "tvl-api-2", "documentType": "ID_CARD", "documentNumber": "11010519491231002X", "holderName": "张三", "segmentRef": "seg-api", "departureDate": JOURNEY_DATE, "seatClass": "SECOND_CLASS", "bookingValueMinor": 10000}, headers={"Idempotency-Key": key(91)})
    assert rejected.status_code == 201
    assert rejected.json()["reason"] == "DUPLICATE_TICKET"
    assert rejected.json()["duplicateTicketCheck"] == "FAIL"
