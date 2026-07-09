from fastapi.testclient import TestClient
from identity_verification import create_app
from identity_verification.application.service import InMemoryStore


def key(n: int) -> str:
    return f"0194f2e0-7b3e-7610-8000-{n:012d}"


def register(client: TestClient, traveler="tvl-1", tail="5", idem=1):
    body = {"travelerId": traveler, "profileSnapshotVersion": "snap-1", "documentType": "ID_CARD", "maskedDocumentNo": "11***********" + tail, "documentHash": "doc-hash-" + tail, "canonicalNameHash": "name-hash", "validUntil": "2027-01-01T00:00:00Z"}
    return client.post("/api/v1/identity-verification/credentials", json=body, headers={"Idempotency-Key": key(idem)})


def verify(client: TestClient, credential, idem=2):
    body = {"travelerId": credential["travelerId"], "credentialRecordId": credential["credentialRecordId"], "purpose": "ORDER_CREATION", "materialFingerprint": credential.get("materialFingerprint", ""), "simPolicyVersion": "sim-tail-v1", "requestedAt": "2026-01-01T00:00:00Z"}
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

    rejected = client.post("/api/v1/identity-verification/pre-order-checks", json={"orderIntentId": "oint-1", "accountId": "acct-1", "offerId": "off-1", "offerVersion": 1, "travelerRefs": ["missing"], "segmentRefs": ["seg-1"], "journeyDate": "2026-02-01", "productCode": "TRAIN", "limitPolicyVersion": "limit-v1", "requestedAt": "2026-01-01T00:00:00Z"}, headers={"Idempotency-Key": key(8)})
    assert rejected.json()["result"] == "REJECT"
    ok = client.post("/api/v1/identity-verification/pre-order-checks", json={"orderIntentId": "oint-2", "accountId": "acct-1", "offerId": "off-1", "offerVersion": 1, "travelerRefs": ["tvl-1"], "segmentRefs": ["seg-1"], "journeyDate": "2026-02-01", "productCode": "TRAIN", "limitPolicyVersion": "limit-v1", "requestedAt": "2026-01-01T00:00:00Z"}, headers={"Idempotency-Key": key(9)})
    assert ok.status_code == 201
    assert ok.json()["result"] == "PASS"
    assert store.take_outbox()


def test_idempotency_replay_and_certificate_query():
    client = TestClient(create_app(store=InMemoryStore()))
    first = register(client, idem=10)
    replay = register(client, idem=10)
    assert replay.status_code == 201
    cred = first.json(); verify(client, cred, 11)
    cert = client.post("/api/v1/identity-verification/eligibility-certificates", json={"travelerId": "tvl-1", "credentialRecordId": cred["credentialRecordId"], "eligibilityType": "STUDENT", "validFrom": "2026-01-01T00:00:00Z", "validUntil": "2026-12-31T00:00:00Z", "policyYear": "2026", "policyVersion": "student-v1", "annualUsageLimit": 4, "applicableProductCodes": ["TRAIN"], "certificateHash": "cert-hash", "evidenceHash": "evidence-hash"}, headers={"Idempotency-Key": key(12)})
    assert cert.status_code == 201
    query = client.get("/api/v1/identity-verification/eligibility-certificates?travelerId=tvl-1&eligibilityType=STUDENT&journeyDate=2026-02-01&productCode=TRAIN")
    assert query.json()["total"] == 1
    assert "evidenceHash" not in query.json()["items"][0]
