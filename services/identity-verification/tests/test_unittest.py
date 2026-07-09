import unittest
from fastapi.testclient import TestClient
from identity_verification import create_app, health, profile
from identity_verification.application.service import InMemoryStore


class IdentityVerificationSmokeTest(unittest.TestCase):
    def test_profile_and_pass_flow(self):
        self.assertEqual(health(), "ok")
        self.assertEqual(profile()["service_id"], "identity-verification")
        client = TestClient(create_app(store=InMemoryStore()))
        credential = client.post("/api/v1/identity-verification/credentials", json={
            "travelerId": "tvl-unit", "profileSnapshotVersion": "snap-1", "documentType": "ID_CARD",
            "maskedDocumentNo": "11***********5", "documentHash": "doc-hash-5", "canonicalNameHash": "name-hash",
            "validUntil": "2027-01-01T00:00:00Z"
        }, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000501"})
        self.assertEqual(credential.status_code, 201)
        stored = client.app.state.identity_verification_store.credentials[credential.json()["credentialRecordId"]]
        verification = client.post("/api/v1/identity-verification/verification-cases", json={
            "travelerId": "tvl-unit", "credentialRecordId": credential.json()["credentialRecordId"],
            "purpose": "ORDER_CREATION", "materialFingerprint": stored.materialFingerprint,
            "simPolicyVersion": "sim-tail-v1", "requestedAt": "2026-01-01T00:00:00Z"
        }, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000502"})
        self.assertEqual(verification.status_code, 201)
        self.assertEqual(verification.json()["status"], "PASSED")


if __name__ == "__main__":
    unittest.main()
