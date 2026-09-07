import unittest
from datetime import UTC, datetime, timedelta
from fastapi.testclient import TestClient
from identity_verification import create_app, health, profile
from identity_verification.application.service import InMemoryStore

# Anchored to the run's wall clock: the service checks credential validity
# against datetime.now(UTC), so an absolute literal here would be a calendar
# bomb that silently expires. Resolved once at import so the credential
# material fingerprint stays stable within a run.
_NOW = datetime.now(UTC).replace(microsecond=0)
# A national ID card is a long-lived document; 5 years outlasts any CI run.
CREDENTIAL_VALID_UNTIL = (_NOW + timedelta(days=365 * 5)).strftime("%Y-%m-%dT%H:%M:%SZ")
REQUESTED_AT = _NOW.strftime("%Y-%m-%dT%H:%M:%SZ")


class IdentityVerificationSmokeTest(unittest.TestCase):
    def test_profile_and_pass_flow(self):
        self.assertEqual(health(), "ok")
        self.assertEqual(profile()["service_id"], "identity-verification")
        client = TestClient(create_app(store=InMemoryStore()))
        credential = client.post("/api/v1/identity-verification/credentials", json={
            "travelerId": "tvl-unit", "profileSnapshotVersion": "snap-1", "documentType": "ID_CARD",
            "maskedDocumentNo": "11***********5", "documentHash": "doc-hash-5", "canonicalNameHash": "name-hash",
            "validUntil": CREDENTIAL_VALID_UNTIL
        }, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000501"})
        self.assertEqual(credential.status_code, 201)
        stored = client.app.state.identity_verification_store.credentials[credential.json()["credentialRecordId"]]
        verification = client.post("/api/v1/identity-verification/verification-cases", json={
            "travelerId": "tvl-unit", "credentialRecordId": credential.json()["credentialRecordId"],
            "purpose": "ORDER_CREATION", "materialFingerprint": stored.materialFingerprint,
            "simPolicyVersion": "sim-tail-v1", "requestedAt": REQUESTED_AT
        }, headers={"Idempotency-Key": "0194f2e0-7b3e-7610-8000-000000000502"})
        self.assertEqual(verification.status_code, 201)
        self.assertEqual(verification.json()["status"], "PASSED")


if __name__ == "__main__":
    unittest.main()
