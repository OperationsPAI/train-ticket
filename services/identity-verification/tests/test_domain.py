from datetime import UTC, datetime, timedelta
import pytest
from identity_verification.domain import CredentialRecord, DomainError, VerificationCase, SimOutcome, EligibilityCertificate


def test_verification_state_transitions_pass_and_manual_override():
    now = datetime(2026, 1, 1, tzinfo=UTC)
    credential = CredentialRecord.register(credential_id="crd-1", traveler_id="tvl-1", profile_snapshot_version="snap-1", document_type="ID_CARD", masked_document_no="11***********0", document_hash="hash0", material_fingerprint="fp", canonical_name_hash="name", birth_date_hash=None, valid_until=now + timedelta(days=1), evidence_hash=None, identity_cluster_id="icl-1", at=now)
    case = VerificationCase.start("ivc-1", "tvl-1", credential.credentialRecordId, "ORDER_CREATION", "fp", "sim-tail-v1", now)
    passed = case.submit_and_record(SimOutcome.MATCH, "sim-1", now)
    assert passed.status == "PASSED"
    manual = VerificationCase.start("ivc-2", "tvl-1", credential.credentialRecordId, "ORDER_CREATION", "fp2", "sim-tail-v1", now).submit_and_record(SimOutcome.MANUAL_REVIEW_REQUIRED, "sim-2", now)
    assert manual.manual_override(now).status == "PASSED"


def test_invalid_certificate_window_rejected():
    now = datetime(2026, 1, 1, tzinfo=UTC)
    with pytest.raises(DomainError):
        EligibilityCertificate.register(certificate_id="elc-1", traveler_id="tvl-1", credential_id="crd-1", cluster_id=None, eligibility_type="STUDENT", valid_from=now, valid_until=now, policy_year="2026", policy_version="v1", annual_usage_limit=4, product_codes=("TRAIN",), certificate_hash="h", evidence_hash="e", at=now)
