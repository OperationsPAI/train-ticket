from __future__ import annotations

from contextlib import nullcontext
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from hashlib import sha256
import re
from typing import Any, Mapping
from uuid import UUID

from train_ticket_platform.events import EventEnvelope, canonical_correlation_id, rfc3339_utc

from identity_verification.domain import (
    ActiveTicket,
    BlacklistChecker,
    BlacklistEntry,
    BlacklistType,
    DuplicateTicketCheck,
    CredentialRecord,
    CredentialStatus,
    DomainError,
    ExpiredDocumentError,
    EligibilityCertificate,
    PreOrderResult,
    PreconditionFailed,
    PurchaseLimitFact,
    SimOutcome,
    VerificationCache,
    VerificationCase,
    VerificationRequest,
    VerificationResult,
    VerificationResultStatus,
    VerificationStatus,
    now_utc,
)
from identity_verification.ids import prefixed_uuid7

PRODUCER = "identity-verification"
TAIL_RE = re.compile(r"(\d)(?!.*\d)")
LEDGER_AGGREGATE_ID = "purchase-limit-ledger"


class NotFoundError(KeyError):
    pass


PreconditionFailedError = PreconditionFailed


def _parse_dt(value: str | None) -> datetime | None:
    if not value:
        return None
    return datetime.fromisoformat(str(value).replace("Z", "+00:00")).astimezone(UTC)


def _folded_uuid(material: str) -> str:
    digest = bytearray(sha256(material.encode("utf-8")).digest()[:16])
    digest[6] = (digest[6] & 0x0F) | 0x70
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(UUID(bytes=bytes(digest)))


def _prefixed_fold(prefix: str, material: str) -> str:
    return f"{prefix}-{_folded_uuid(material)}"


def _event_id(event_type: str, aggregate_id: str, version: int, fact_id: str | None = None) -> str:
    material = f"{PRODUCER}:{event_type}:{aggregate_id}:{fact_id + ':' if fact_id else ''}{version}"
    return _prefixed_fold("evt", material)


def _command_id(value: str | None) -> str:
    if value and value.startswith(("cmd-", "evt-")):
        return value
    return f"cmd-{value}" if value else prefixed_uuid7("cmd")


def _envelope(event_type: str, aggregate_id: str, version: int, payload: Mapping[str, Any], correlation_id: str, causation_id: str | None, occurred_at: datetime, fact_id: str | None = None) -> EventEnvelope:
    return EventEnvelope(eventId=_event_id(event_type, aggregate_id, version, fact_id), eventType=event_type, occurredAt=occurred_at, correlationId=canonical_correlation_id(correlation_id), causationId=_command_id(causation_id), producer=PRODUCER, schemaVersion=1, payload=payload)


def _safe_hash(*parts: object) -> str:
    return sha256("|".join("" if p is None else str(p) for p in parts).encode("utf-8")).hexdigest()


def _pre_order_material(data: Mapping[str, Any]) -> str:
    return _safe_hash(
        data.get("orderIntentId"), data.get("accountId"),
        ",".join(sorted(str(x) for x in data.get("travelerRefs") or [])),
        ",".join(sorted(str(x) for x in data.get("segmentRefs") or [])),
        data.get("journeyDate"), data.get("productCode"), data.get("limitPolicyVersion"),
        ",".join(sorted(str(x) for x in data.get("requestedEligibilityTypes") or [])),
    )


class InMemoryStore:
    def __init__(self) -> None:
        self.credentials: dict[str, CredentialRecord] = {}
        self.credential_duplicate_index: dict[tuple[str, str, str, str, str], str] = {}
        self.cases: dict[str, VerificationCase] = {}
        self.case_duplicate_index: dict[tuple[str, str, str, str, str], str] = {}
        self.certificates: dict[str, EligibilityCertificate] = {}
        self.certificate_duplicate_index: dict[tuple[str, str, str, str, str], str] = {}
        self.facts: dict[str, PurchaseLimitFact] = {}
        self.fact_duplicate_index: dict[tuple[str, str, str, str, str], str] = {}
        self.pre_order_checks: dict[str, dict[str, Any]] = {}
        self.pre_order_duplicate_index: dict[str, str] = {}
        self.traveler_snapshots: dict[str, dict[str, Any]] = {}
        self.blacklist_entries: list[BlacklistEntry] = []
        self.active_tickets: dict[tuple[str, str, str], ActiveTicket] = {}
        self.verification_cache = VerificationCache()
        self._outbox: list[EventEnvelope] = []
        self.processed_events: set[str] = set()

    def transaction(self) -> Any:
        return nullcontext()

    def unit_of_work(self) -> Any:
        return nullcontext()

    def append_outbox(self, envelopes: tuple[EventEnvelope, ...]) -> None:
        self._outbox.extend(envelopes)

    def take_outbox(self) -> tuple[EventEnvelope, ...]:
        out = tuple(self._outbox); self._outbox.clear(); return out

    def mark_processed(self, event_id: str, stream: str | None = None) -> bool:
        if event_id in self.processed_events: return False
        self.processed_events.add(event_id); return True

    def save_traveler_snapshot(self, traveler_id: str, data: Mapping[str, Any]) -> None:
        self.traveler_snapshots[traveler_id] = dict(data)

    def get_credential(self, credential_id: str) -> CredentialRecord:
        try: return self.credentials[credential_id]
        except KeyError as exc: raise NotFoundError(f"credential not found: {credential_id}") from exc

    def save_credential(self, credential: CredentialRecord) -> None:
        self.credentials[credential.credentialRecordId] = credential
        self.credential_duplicate_index[(credential.travelerId, credential.documentType, credential.documentHash, credential.materialFingerprint, credential.profileSnapshotVersion)] = credential.credentialRecordId

    def find_credential_duplicate(self, traveler_id: str, document_type: str, document_hash: str, material_fingerprint: str, snapshot_version: str) -> CredentialRecord | None:
        cid = self.credential_duplicate_index.get((traveler_id, document_type, document_hash, material_fingerprint, snapshot_version))
        return self.credentials.get(cid) if cid else None

    def credentials_for_traveler(self, traveler_id: str) -> tuple[CredentialRecord, ...]:
        return tuple(c for c in self.credentials.values() if c.travelerId == traveler_id)

    def get_case(self, case_id: str) -> VerificationCase:
        try: return self.cases[case_id]
        except KeyError as exc: raise NotFoundError(f"verification case not found: {case_id}") from exc

    def save_case(self, case: VerificationCase) -> None:
        self.cases[case.verificationCaseId] = case
        self.case_duplicate_index[(case.travelerId, case.credentialRecordId, case.purpose, case.materialFingerprint, case.simPolicyVersion)] = case.verificationCaseId

    def find_case_duplicate(self, traveler_id: str, credential_id: str, purpose: str, material_fingerprint: str, policy: str) -> VerificationCase | None:
        cid = self.case_duplicate_index.get((traveler_id, credential_id, purpose, material_fingerprint, policy))
        return self.cases.get(cid) if cid else None

    def find_case_by_credential(self, credential_id: str) -> VerificationCase | None:
        cases = [case for case in self.cases.values() if case.credentialRecordId == credential_id]
        return max(cases, key=lambda item: item.createdAt, default=None)

    def get_certificate(self, certificate_id: str) -> EligibilityCertificate:
        try: return self.certificates[certificate_id]
        except KeyError as exc: raise NotFoundError(f"eligibility certificate not found: {certificate_id}") from exc

    def save_certificate(self, certificate: EligibilityCertificate) -> None:
        self.certificates[certificate.eligibilityCertificateId] = certificate
        self.certificate_duplicate_index[(certificate.travelerId, certificate.eligibilityType, certificate.certificateHash, certificate.policyYear, certificate.policyVersion)] = certificate.eligibilityCertificateId

    def find_certificate_duplicate(self, traveler_id: str, eligibility_type: str, certificate_hash: str, policy_year: str, policy_version: str) -> EligibilityCertificate | None:
        cid = self.certificate_duplicate_index.get((traveler_id, eligibility_type, certificate_hash, policy_year, policy_version))
        return self.certificates.get(cid) if cid else None

    def query_certificates(self, traveler_id: str, eligibility_type: str | None, journey_date: str, product_code: str | None, limit: int, offset: int) -> tuple[tuple[EligibilityCertificate, ...], int]:
        items = [c for c in self.certificates.values() if c.travelerId == traveler_id and (eligibility_type is None or c.eligibilityType == eligibility_type) and c.is_active_for(journey_date, product_code)]
        items.sort(key=lambda c: c.createdAt)
        return tuple(items[offset:offset + limit]), len(items)

    def save_fact(self, fact: PurchaseLimitFact) -> None:
        self.facts[fact.purchaseLimitFactId] = fact
        self.fact_duplicate_index[(fact.scopeType, fact.scopeRef, fact.journeyDate, fact.productCode, fact.orderIntentId)] = fact.purchaseLimitFactId

    def find_fact_duplicate(self, scope_type: str, scope_ref: str, journey_date: str, product_code: str, order_intent_id: str) -> PurchaseLimitFact | None:
        fid = self.fact_duplicate_index.get((scope_type, scope_ref, journey_date, product_code, order_intent_id))
        return self.facts.get(fid) if fid else None

    def get_fact(self, fact_id: str) -> PurchaseLimitFact:
        try: return self.facts[fact_id]
        except KeyError as exc: raise NotFoundError(f"purchase limit fact not found: {fact_id}") from exc

    def save_pre_order_check(self, record: Mapping[str, Any]) -> None:
        data = dict(record)
        self.pre_order_checks[str(data["preOrderCheckId"])] = data
        self.pre_order_duplicate_index[str(data["materialHash"])] = str(data["preOrderCheckId"])

    def get_pre_order_check(self, pre_order_check_id: str) -> dict[str, Any]:
        try: return dict(self.pre_order_checks[pre_order_check_id])
        except KeyError as exc: raise NotFoundError(f"pre-order check not found: {pre_order_check_id}") from exc

    def find_pre_order_check_duplicate(self, material_hash: str) -> dict[str, Any] | None:
        cid = self.pre_order_duplicate_index.get(material_hash)
        return self.get_pre_order_check(cid) if cid else None


    def add_blacklist_entry(self, entry: BlacklistEntry) -> None:
        self.blacklist_entries.append(entry)

    def list_blacklist_entries(self, document_number: str) -> tuple[BlacklistEntry, ...]:
        return tuple(entry for entry in self.blacklist_entries if entry.documentNumber == document_number)

    def find_active_ticket(self, document_number: str, segment_ref: str, departure_date: str) -> ActiveTicket | None:
        ticket = self.active_tickets.get((document_number, segment_ref, departure_date))
        return ticket if ticket is not None and ticket.status == "ACTIVE" else None

    def save_active_ticket(self, ticket: ActiveTicket) -> None:
        self.active_tickets[ticket.key] = ticket

    def list_active_tickets_for_order(self, order_id: str) -> tuple[ActiveTicket, ...]:
        return tuple(ticket for ticket in self.active_tickets.values() if ticket.orderId == order_id and ticket.status == "ACTIVE")

    def release_active_tickets_for_order(self, order_id: str) -> None:
        for key, ticket in list(self.active_tickets.items()):
            if ticket.orderId == order_id:
                self.active_tickets[key] = ActiveTicket(ticket.documentNumber, ticket.segmentRef, ticket.departureDate, ticket.orderId, "CANCELLED")

    def find_reserved_pre_order_check_for_order_event(self, account_id: str, traveler_ids: tuple[str, ...], segment_refs: tuple[str, ...]) -> dict[str, Any] | None:
        traveler_set = set(traveler_ids)
        segment_set = set(segment_refs)
        candidates = []
        for record in self.pre_order_checks.values():
            body = dict(record.get("body") or {})
            if record.get("status") != "RESERVED" or body.get("result") != PreOrderResult.PASS.value:
                continue
            if body.get("accountId") != account_id:
                continue
            if set(str(item) for item in body.get("travelerRefs") or ()) != traveler_set:
                continue
            if set(str(item) for item in body.get("segmentRefs") or ()) != segment_set:
                continue
            candidates.append(record)
        if not candidates:
            return None
        return dict(max(candidates, key=lambda item: str((item.get("body") or {}).get("evaluatedAt") or "")))

    def get_cached_verification(self, traveler_id: str, document_number: str, at: datetime) -> tuple[datetime, datetime] | None:
        return self.verification_cache.get_valid(traveler_id, document_number, at)

    def find_cached_document_for_traveler(self, traveler_id: str, at: datetime) -> str | None:
        return self.verification_cache.find_valid_document_for_traveler(traveler_id, at)

    def save_cached_verification(self, traveler_id: str, document_number: str, verified_at: datetime, expires_at: datetime) -> None:
        self.verification_cache.record(traveler_id, document_number, verified_at, expires_at)


class DeterministicSimGateway:
    def __init__(self, seed: str = "sim-tail-v1") -> None:
        self.seed = seed

    def verify(self, credential: CredentialRecord, material_fingerprint: str, policy_version: str) -> tuple[SimOutcome, str]:
        material = credential.documentHash or material_fingerprint
        match = TAIL_RE.search(material)
        if match is None:
            folded = int(sha256((self.seed + material).encode()).hexdigest(), 16) % 10
        else:
            folded = int(match.group(1))
        if folded <= 5:
            outcome = SimOutcome.MATCH
        elif folded <= 8:
            outcome = SimOutcome.REJECTED
        else:
            outcome = SimOutcome.MANUAL_REVIEW_REQUIRED
        ref_material = f"{self.seed}:{credential.credentialRecordId}:{material_fingerprint}:{policy_version}:{outcome.value}"
        return outcome, _prefixed_fold("sim", ref_material)


class IdentityVerificationService:
    def __init__(self, store: Any, sim: DeterministicSimGateway | None = None) -> None:
        self.store = store
        self.sim = sim or DeterministicSimGateway()

    def transaction(self) -> Any:
        tx = getattr(self.store, "transaction", None)
        return tx() if callable(tx) else nullcontext()

    def _append(self, envelopes: tuple[EventEnvelope, ...]) -> None:
        append = getattr(self.store, "append_outbox", None)
        if callable(append): append(envelopes)

    def verify_identity(self, data: Mapping[str, Any], correlation_id: str, causation_id: str | None) -> dict[str, Any]:
        with self.transaction():
            at = now_utc()
            request = VerificationRequest.from_mapping(data)
            duplicate_check = DuplicateTicketCheck.PASS
            envelopes: list[EventEnvelope] = []
            try:
                request.validate(at)
            except ExpiredDocumentError:
                result = VerificationResult(VerificationResultStatus.EXPIRED, "EXPIRED_DOCUMENT", None, None, duplicateTicketCheck=duplicate_check)
                envelopes.append(self._identity_rejected_event(request.travelerId, "EXPIRED_DOCUMENT", corr=correlation_id, cause=causation_id, at=at))
                self._append(tuple(envelopes))
                return self._verification_response(request, result)

            if request.segmentRef and request.departureDate:
                existing_ticket = self.store.find_active_ticket(request.documentNumber, request.segmentRef, request.departureDate)
                if existing_ticket is not None:
                    duplicate_check = DuplicateTicketCheck.FAIL
                    result = VerificationResult(VerificationResultStatus.REJECTED, "DUPLICATE_TICKET", None, None, duplicateTicketCheck=duplicate_check)
                    envelopes.append(self._identity_rejected_event(request.travelerId, "DUPLICATE_TICKET", corr=correlation_id, cause=causation_id, at=at))
                    self._append(tuple(envelopes))
                    return self._verification_response(request, result)

            blacklist_hit = self._blacklist_hit(request, at)
            if blacklist_hit is not None:
                entry, status, reason, restrictions = blacklist_hit
                result = VerificationResult(status, reason, None, None, restrictions=restrictions, duplicateTicketCheck=duplicate_check)
                envelopes.append(self._blacklist_hit_event(entry, corr=correlation_id, cause=causation_id, at=at))
                envelopes.append(self._identity_rejected_event(request.travelerId, reason, corr=correlation_id, cause=causation_id, at=at))
                self._append(tuple(envelopes))
                return self._verification_response(request, result)

            cache_lookup = self.store.get_cached_verification(request.travelerId, request.documentNumber, at)
            cached = None if request.bookingValueMinor > 500_000 else cache_lookup
            if cached is not None:
                verified_at, expires_at = cached
                result = VerificationResult(VerificationResultStatus.VERIFIED, None, verified_at, expires_at, duplicateTicketCheck=duplicate_check, cacheHit=True)
                return self._verification_response(request, result)

            expires_at = at + timedelta(days=request.documentType.validity_days)
            result = VerificationResult(VerificationResultStatus.VERIFIED, None, at, expires_at, duplicateTicketCheck=duplicate_check)
            self.store.save_cached_verification(request.travelerId, request.documentNumber, at, expires_at)
            envelopes.append(self._identity_verified_event(request, expires_at, correlation_id, causation_id, at))
            self._append(tuple(envelopes))
            return self._verification_response(request, result)

    def _blacklist_hit(self, request: VerificationRequest, at: datetime) -> tuple[BlacklistEntry, VerificationResultStatus, str, tuple[str, ...]] | None:
        hit = BlacklistChecker(self.store.list_blacklist_entries(request.documentNumber)).check(request.documentNumber, request.seatClass, at)
        if hit is None:
            return None
        entry, (status, reason, restrictions) = hit
        return entry, status, reason, restrictions

    def _verification_response(self, request: VerificationRequest, result: VerificationResult) -> dict[str, Any]:
        return {"travelerId": request.travelerId, "documentType": request.documentType.value, **result.to_json()}

    def _identity_verified_event(self, request: VerificationRequest, expires_at: datetime, corr: str, cause: str | None, at: datetime) -> EventEnvelope:
        payload = {"travelerId": request.travelerId, "documentType": request.documentType.value, "verifiedAt": rfc3339_utc(at), "expiresAt": rfc3339_utc(expires_at)}
        version = int(at.timestamp() * 1_000_000)
        return _envelope("IdentityVerified", f"{request.travelerId}:{request.documentNumber}", version, payload, corr, cause, at)

    def _identity_rejected_event(self, traveler_id: str, reason: str, corr: str, cause: str | None, at: datetime) -> EventEnvelope:
        version = int(at.timestamp() * 1_000_000)
        return _envelope("IdentityRejected", traveler_id, version, {"travelerId": traveler_id, "reason": reason, "rejectedAt": rfc3339_utc(at)}, corr, cause, at)

    def _blacklist_hit_event(self, entry: BlacklistEntry, corr: str, cause: str | None, at: datetime) -> EventEnvelope:
        payload = {"documentNumber": entry.documentNumber, "blacklistType": entry.blacklistType.value, "reason": entry.reason, "hitAt": rfc3339_utc(at)}
        version = int(at.timestamp() * 1_000_000)
        return _envelope("BlacklistHit", entry.documentNumber, version, payload, corr, cause, at)

    def register_credential(self, data: Mapping[str, Any], correlation_id: str, causation_id: str | None) -> dict[str, Any]:
        with self.transaction():
            at = now_utc()
            material_fingerprint = str(data.get("materialFingerprint") or _safe_hash(data.get("canonicalNameHash"), data.get("documentType"), data.get("documentHash"), data.get("birthDateHash"), data.get("validUntil"), data.get("evidenceHash"), data.get("profileSnapshotVersion")))
            duplicate = self.store.find_credential_duplicate(str(data["travelerId"]), str(data["documentType"]), str(data["documentHash"]), material_fingerprint, str(data["profileSnapshotVersion"]))
            if duplicate is not None:
                return duplicate.to_json()
            cluster_id = _prefixed_fold("icl", f"identity-cluster:{data['documentHash']}")
            credential = CredentialRecord.register(credential_id=prefixed_uuid7("crd"), traveler_id=str(data["travelerId"]), profile_snapshot_version=str(data["profileSnapshotVersion"]), document_type=str(data["documentType"]), masked_document_no=str(data["maskedDocumentNo"]), document_hash=str(data["documentHash"]), material_fingerprint=material_fingerprint, canonical_name_hash=str(data["canonicalNameHash"]), birth_date_hash=data.get("birthDateHash"), valid_until=_parse_dt(data.get("validUntil")), evidence_hash=data.get("evidenceHash"), identity_cluster_id=cluster_id, at=at)
            self.store.save_credential(credential)
            payload = {**credential.to_json(), "materialFingerprint": credential.materialFingerprint, "credentialStatus": credential.status.value, "registeredAt": rfc3339_utc(at), "aggregateVersion": 1}
            payload.pop("status", None); payload.pop("createdAt", None); payload.pop("updatedAt", None)
            self._append((_envelope("CredentialRegistered", credential.credentialRecordId, 1, payload, correlation_id, causation_id, at),))
            return credential.to_json()

    def start_verification_case(self, data: Mapping[str, Any], correlation_id: str, causation_id: str | None) -> dict[str, Any]:
        with self.transaction():
            at = now_utc()
            credential = self.store.get_credential(str(data["credentialRecordId"]))
            if credential.travelerId != str(data["travelerId"]):
                raise DomainError("credential does not belong to traveler")
            if credential.materialFingerprint != str(data["materialFingerprint"]):
                raise DomainError("materialFingerprint does not match credential")
            duplicate = self.store.find_case_duplicate(str(data["travelerId"]), credential.credentialRecordId, str(data["purpose"]), str(data["materialFingerprint"]), str(data["simPolicyVersion"]))
            if duplicate is not None:
                return duplicate.to_json()
            case = VerificationCase.start(prefixed_uuid7("ivc"), credential.travelerId, credential.credentialRecordId, str(data["purpose"]), str(data["materialFingerprint"]), str(data["simPolicyVersion"]), at)
            credential = credential.mark_pending(at)
            outcome, sim_ref = self.sim.verify(credential, case.materialFingerprint, case.simPolicyVersion)
            submitted = case.submit_and_record(outcome, sim_ref, at)
            credential = credential.mark_verified(submitted.verificationCaseId, at) if submitted.status is VerificationStatus.PASSED else credential.mark_failed(at)
            self.store.save_credential(credential)
            self.store.save_case(submitted)
            envelopes = [self._case_started(case, correlation_id, causation_id, at), self._submitted_to_sim(submitted, correlation_id, causation_id, at), self._case_result(submitted, correlation_id, causation_id, at)]
            self._append(tuple(envelopes))
            return submitted.to_json()

    def manual_override(self, case_id: str, correlation_id: str, causation_id: str | None) -> dict[str, Any]:
        with self.transaction():
            at = now_utc()
            case = self.store.get_case(case_id).manual_override(at)
            credential = self.store.get_credential(case.credentialRecordId).mark_verified(case.verificationCaseId, at)
            self.store.save_case(case); self.store.save_credential(credential)
            self._append((self._case_result(case, correlation_id, causation_id, at),))
            return case.to_json()

    def _case_started(self, case: VerificationCase, corr: str, cause: str | None, at: datetime) -> EventEnvelope:
        payload = {"verificationCaseId": case.verificationCaseId, "travelerId": case.travelerId, "credentialRecordId": case.credentialRecordId, "purpose": case.purpose, "materialFingerprint": case.materialFingerprint, "verificationStatus": "DRAFT", "simPolicyVersion": case.simPolicyVersion, "startedAt": rfc3339_utc(at), "aggregateVersion": 1}
        return _envelope("VerificationCaseStarted", case.verificationCaseId, 1, payload, corr, cause, at)

    def _submitted_to_sim(self, case: VerificationCase, corr: str, cause: str | None, at: datetime) -> EventEnvelope:
        payload = {"verificationCaseId": case.verificationCaseId, "travelerId": case.travelerId, "credentialRecordId": case.credentialRecordId, "simOperationRef": "simop-" + case.simResultRef.removeprefix("sim-"), "materialFingerprint": case.materialFingerprint, "simPolicyVersion": case.simPolicyVersion, "submittedAt": rfc3339_utc(at), "verificationStatus": "SUBMITTED", "aggregateVersion": 2}
        return _envelope("VerificationSubmittedToSim", case.verificationCaseId, 2, payload, corr, cause, at)

    def _case_result(self, case: VerificationCase, corr: str, cause: str | None, at: datetime) -> EventEnvelope:
        version = case.version
        if case.status is VerificationStatus.PASSED:
            payload = {"verificationCaseId": case.verificationCaseId, "travelerId": case.travelerId, "credentialRecordId": case.credentialRecordId, "simOutcome": "MATCH", "simResultRef": case.simResultRef or "sim-override", "verificationStatus": "PASSED", "validFrom": rfc3339_utc(case.validFrom or at), "validUntil": rfc3339_utc(case.validUntil or at + timedelta(days=365)), "policyVersion": case.simPolicyVersion, "completedAt": rfc3339_utc(case.completedAt or at), "aggregateVersion": version}
            return _envelope("VerificationPassed", case.verificationCaseId, version, payload, corr, cause, at)
        payload = {"verificationCaseId": case.verificationCaseId, "travelerId": case.travelerId, "credentialRecordId": case.credentialRecordId, "simOutcome": case.simOutcome.value if case.simOutcome else "REJECTED", "simResultRef": case.simResultRef or "sim-unavailable", "verificationStatus": case.status.value, "reasonCode": case.reasonCode or "NAME_DOCUMENT_MISMATCH", "policyVersion": case.simPolicyVersion, "completedAt": rfc3339_utc(case.completedAt or at), "aggregateVersion": version}
        return _envelope("VerificationFailed", case.verificationCaseId, version, payload, corr, cause, at)

    def credential_status(self, credential_id: str) -> dict[str, Any]:
        at = now_utc(); c = self.store.get_credential(credential_id)
        latest = self.store.get_case(c.verifiedByCaseId) if c.verifiedByCaseId else self.store.find_case_by_credential(credential_id)
        valid_until = (latest.validUntil if latest else None) or c.validUntil
        return {"credentialRecordId": c.credentialRecordId, "travelerId": c.travelerId, "credentialStatus": c.status.value, "latestVerificationCaseId": latest.verificationCaseId if latest else None, "verificationStatus": latest.status.value if latest else "DRAFT", "validUntil": rfc3339_utc(valid_until) if valid_until else None, "reasonCode": latest.reasonCode if latest else None, "readAt": rfc3339_utc(at)}

    def register_certificate(self, data: Mapping[str, Any], corr: str, cause: str | None) -> dict[str, Any]:
        with self.transaction():
            at = now_utc()
            duplicate = self.store.find_certificate_duplicate(str(data["travelerId"]), str(data["eligibilityType"]), str(data["certificateHash"]), str(data["policyYear"]), str(data["policyVersion"]))
            if duplicate is not None: return duplicate.to_json()
            credential_id = data.get("credentialRecordId")
            if credential_id:
                credential = self.store.get_credential(str(credential_id))
                if credential.status is not CredentialStatus.VERIFIED:
                    raise PreconditionFailed("credential is not verified")
            cert = EligibilityCertificate.register(certificate_id=prefixed_uuid7("elc"), traveler_id=str(data["travelerId"]), credential_id=str(credential_id) if credential_id else None, cluster_id=data.get("identityClusterId"), eligibility_type=str(data["eligibilityType"]), valid_from=_parse_dt(str(data["validFrom"])) or at, valid_until=_parse_dt(str(data["validUntil"])) or at, policy_year=str(data["policyYear"]), policy_version=str(data["policyVersion"]), annual_usage_limit=int(data["annualUsageLimit"]), product_codes=tuple(str(x) for x in data["applicableProductCodes"]), certificate_hash=str(data["certificateHash"]), evidence_hash=str(data["evidenceHash"]), at=at)
            cert = replace(cert, version=2)
            self.store.save_certificate(cert)
            base = cert.to_json(include_evidence=True)
            payload = {k: v for k, v in base.items() if k not in {"status", "createdAt", "updatedAt"}}
            payload.update({"certificateStatus": "DRAFT", "certificateHash": cert.certificateHash, "evidenceHash": cert.evidenceHash, "registeredAt": rfc3339_utc(at), "aggregateVersion": 1})
            verified = {k: v for k, v in payload.items() if k not in {"certificateHash", "evidenceHash", "registeredAt"}}
            verified.update({"certificateStatus": "ACTIVE", "verificationAttemptId": prefixed_uuid7("eva"), "verifiedAt": rfc3339_utc(at), "aggregateVersion": 2})
            self._append((_envelope("EligibilityCertificateRegistered", cert.eligibilityCertificateId, 1, payload, corr, cause, at), _envelope("EligibilityCertificateVerified", cert.eligibilityCertificateId, 2, verified, corr, cause, at)))
            return cert.to_json()

    def query_certificates(self, traveler_id: str, eligibility_type: str | None, journey_date: str, product_code: str | None, limit: int, offset: int) -> dict[str, Any]:
        items, total = self.store.query_certificates(traveler_id, eligibility_type, journey_date, product_code, limit, offset)
        return {"items": [item.to_json(include_evidence=False) for item in items], "total": total, "limit": limit, "offset": offset}

    def pre_order_check(self, data: Mapping[str, Any], corr: str, cause: str | None) -> tuple[dict[str, Any], bool]:
        with self.transaction():
            material_hash = _pre_order_material(data)
            existing_record = self.store.find_pre_order_check_duplicate(material_hash)
            if existing_record is not None:
                return dict(existing_record["body"]), False

            at = now_utc(); checks: list[dict[str, Any]] = []; facts: list[dict[str, Any]] = []
            candidate_facts: list[PurchaseLimitFact] = []; candidate_certificates: list[EligibilityCertificate] = []
            result = PreOrderResult.PASS
            traveler_refs = [str(x) for x in data["travelerRefs"]]
            for traveler_id in traveler_refs:
                creds = [c for c in self.store.credentials_for_traveler(traveler_id) if c.status is CredentialStatus.VERIFIED and (c.validUntil is None or c.validUntil > at)]
                if not creds:
                    checks.append({"travelerId": traveler_id, "code": "VERIFICATION_NOT_PASSED", "status": "REJECT", "reasonCode": "NO_VERIFIED_CREDENTIAL", "policyVersion": str(data["limitPolicyVersion"])})
                    result = PreOrderResult.REJECT
                    continue
                credential = creds[0]
                checks.append({"travelerId": traveler_id, "credentialRecordId": credential.credentialRecordId, "code": "VERIFICATION_PASSED", "status": "PASS", "policyVersion": str(data["limitPolicyVersion"])})
                for eligibility_type in data.get("requestedEligibilityTypes") or []:
                    certs, _ = self.store.query_certificates(traveler_id, str(eligibility_type), str(data["journeyDate"]), str(data["productCode"]), 1, 0)
                    if certs:
                        candidate_certificates.append(certs[0])
                        checks.append({"travelerId": traveler_id, "credentialRecordId": credential.credentialRecordId, "eligibilityCertificateId": certs[0].eligibilityCertificateId, "code": "ELIGIBILITY_ACTIVE", "status": "PASS", "policyVersion": certs[0].policyVersion})
                    else:
                        checks.append({"travelerId": traveler_id, "credentialRecordId": credential.credentialRecordId, "code": "ELIGIBILITY_UNAVAILABLE", "status": "REJECT", "reasonCode": "NO_ACTIVE_CERTIFICATE", "policyVersion": str(data["limitPolicyVersion"])})
                        result = PreOrderResult.REJECT
                existing = self.store.find_fact_duplicate("CREDENTIAL", credential.credentialRecordId, str(data["journeyDate"]), str(data["productCode"]), str(data["orderIntentId"]))
                if existing is None:
                    candidate_facts.append(PurchaseLimitFact(prefixed_uuid7("plf"), "CREDENTIAL", credential.credentialRecordId, traveler_id, str(data["orderIntentId"]), str(data["journeyDate"]), str(data["productCode"]), tuple(str(x) for x in data["segmentRefs"]), str(data["limitPolicyVersion"]), "RECORDED", at, 1))
                else:
                    facts.append({"purchaseLimitFactId": existing.purchaseLimitFactId, "scopeType": existing.scopeType, "scopeRef": existing.scopeRef, "status": existing.status, "limitPolicyVersion": existing.limitPolicyVersion})
                checks.append({"travelerId": traveler_id, "credentialRecordId": credential.credentialRecordId, "code": "PURCHASE_LIMIT_RECORDED", "status": "PASS", "policyVersion": str(data["limitPolicyVersion"])})

            body = {"preOrderCheckId": _prefixed_fold("poc", f"pre-order:{material_hash}"), "orderIntentId": str(data["orderIntentId"]), "accountId": str(data["accountId"]), "travelerRefs": traveler_refs, "segmentRefs": [str(x) for x in data["segmentRefs"]], "journeyDate": str(data["journeyDate"]), "productCode": str(data["productCode"]), "result": result.value, "checks": checks, "purchaseLimitFacts": facts, "evaluatedAt": rfc3339_utc(at), "expiresAt": rfc3339_utc(at + timedelta(minutes=10))}
            record: dict[str, Any] = {"preOrderCheckId": body["preOrderCheckId"], "materialHash": material_hash, "status": "REJECTED" if result is PreOrderResult.REJECT else "RESERVED", "body": body, "usageReservations": [], "purchaseLimitFactIds": []}
            envelopes: list[EventEnvelope] = []
            if result is PreOrderResult.PASS:
                for certificate in candidate_certificates:
                    reservation_id = _prefixed_fold("eur", f"{certificate.eligibilityCertificateId}:{certificate.policyYear}:{data['orderIntentId']}")
                    reserved = certificate.reserve(at); version = reserved.version
                    self.store.save_certificate(reserved)
                    record["usageReservations"].append({"usageReservationId": reservation_id, "eligibilityCertificateId": reserved.eligibilityCertificateId, "status": "RESERVED"})
                    envelopes.append(self._eligibility_reserved_event(reserved, reservation_id, str(data["orderIntentId"]), version, corr, cause, at))
                for fact in candidate_facts:
                    self.store.save_fact(fact); record["purchaseLimitFactIds"].append(fact.purchaseLimitFactId)
                    facts.append({"purchaseLimitFactId": fact.purchaseLimitFactId, "scopeType": fact.scopeType, "scopeRef": fact.scopeRef, "status": fact.status, "limitPolicyVersion": fact.limitPolicyVersion})
                    envelopes.append(self._fact_recorded_event(fact, corr, cause, at))
                body["purchaseLimitFacts"] = facts
            self.store.save_pre_order_check(record)
            self._append(tuple(envelopes))
            return body, bool(envelopes)

    def confirm_pre_order_check(self, pre_order_check_id: str, journey_order_id: str, corr: str, cause: str | None) -> dict[str, Any]:
        with self.transaction():
            record = self.store.get_pre_order_check(pre_order_check_id)
            if record.get("status") == "CONFIRMED": return dict(record["body"])
            if record.get("status") == "RELEASED": raise PreconditionFailed("pre-order check is already released")
            at = now_utc(); envelopes: list[EventEnvelope] = []
            for reservation in record.get("usageReservations", []):
                if reservation.get("status") != "RESERVED": continue
                certificate = self.store.get_certificate(str(reservation["eligibilityCertificateId"]))
                confirmed = certificate.confirm(at); version = confirmed.version
                self.store.save_certificate(confirmed); reservation["status"] = "CONFIRMED"
                envelopes.append(self._eligibility_confirmed_event(confirmed, str(reservation["usageReservationId"]), journey_order_id, version, corr, cause, at))
            for fact_id in record.get("purchaseLimitFactIds", []):
                fact = self.store.get_fact(str(fact_id))
                if fact.status != "RECORDED": continue
                confirmed_fact = fact.confirm(journey_order_id, at)
                self.store.save_fact(confirmed_fact)
                envelopes.append(self._fact_confirmed_event(confirmed_fact, journey_order_id, corr, cause, at))
            record["status"] = "CONFIRMED"; self.store.save_pre_order_check(record); self._append(tuple(envelopes))
            return dict(record["body"])

    def release_pre_order_check(self, pre_order_check_id: str, release_reason: str, corr: str, cause: str | None, source_event_id: str | None = None) -> dict[str, Any]:
        with self.transaction():
            record = self.store.get_pre_order_check(pre_order_check_id)
            if record.get("status") == "RELEASED": return dict(record["body"])
            if record.get("status") == "CONFIRMED": raise PreconditionFailed("pre-order check is already confirmed")
            at = now_utc(); body = dict(record["body"]); envelopes: list[EventEnvelope] = []
            for reservation in record.get("usageReservations", []):
                if reservation.get("status") != "RESERVED": continue
                certificate = self.store.get_certificate(str(reservation["eligibilityCertificateId"]))
                released = certificate.release(at); version = released.version
                self.store.save_certificate(released); reservation["status"] = "RELEASED"
                envelopes.append(self._eligibility_released_event(released, str(reservation["usageReservationId"]), body["orderIntentId"], release_reason, version, corr, cause, at))
            for fact_id in record.get("purchaseLimitFactIds", []):
                fact = self.store.get_fact(str(fact_id))
                if fact.status != "RECORDED": continue
                released_fact = fact.release(release_reason, at, source_event_id)
                self.store.save_fact(released_fact)
                envelopes.append(self._fact_released_event(released_fact, release_reason, source_event_id, corr, cause, at))
            record["status"] = "RELEASED"; self.store.save_pre_order_check(record); self._append(tuple(envelopes))
            return body


    def mark_purchase_limit_missed(self, purchase_limit_fact_id: str, ttl_bucket: str, monitor_run_id: str, corr: str, cause: str | None) -> dict[str, Any]:
        with self.transaction():
            at = now_utc()
            fact = self.store.get_fact(purchase_limit_fact_id)
            missed = fact.miss(ttl_bucket, monitor_run_id, at)
            self.store.save_fact(missed)
            self._append((self._fact_missed_event(missed, ttl_bucket, monitor_run_id, corr, cause, at),))
            return missed.to_json()

    def mark_purchase_limit_failed(self, purchase_limit_fact_id: str, failure_code: str, detection_run_id: str, corr: str, cause: str | None) -> dict[str, Any]:
        with self.transaction():
            at = now_utc()
            fact = self.store.get_fact(purchase_limit_fact_id)
            failed = fact.fail(failure_code, detection_run_id, at)
            self.store.save_fact(failed)
            self._append((self._fact_failed_event(failed, failure_code, detection_run_id, corr, cause, at),))
            return failed.to_json()

    def _eligibility_reserved_event(self, certificate: EligibilityCertificate, reservation_id: str, order_intent_id: str, version: int, corr: str, cause: str | None, at: datetime) -> EventEnvelope:
        return _envelope("EligibilityUsageReserved", certificate.eligibilityCertificateId, version, {"usageReservationId": reservation_id, "eligibilityCertificateId": certificate.eligibilityCertificateId, "travelerId": certificate.travelerId, "eligibilityType": certificate.eligibilityType, "policyYear": certificate.policyYear, "orderIntentId": order_intent_id, "annualUsageReserved": certificate.annualUsageReserved, "annualUsageConfirmed": certificate.annualUsageConfirmed, "reservedAt": rfc3339_utc(at), "aggregateVersion": version}, corr, cause, at)

    def _eligibility_confirmed_event(self, certificate: EligibilityCertificate, reservation_id: str, journey_order_id: str, version: int, corr: str, cause: str | None, at: datetime) -> EventEnvelope:
        return _envelope("EligibilityUsageConfirmed", certificate.eligibilityCertificateId, version, {"usageReservationId": reservation_id, "eligibilityCertificateId": certificate.eligibilityCertificateId, "travelerId": certificate.travelerId, "journeyOrderId": journey_order_id, "policyYear": certificate.policyYear, "annualUsageReserved": certificate.annualUsageReserved, "annualUsageConfirmed": certificate.annualUsageConfirmed, "confirmedAt": rfc3339_utc(at), "aggregateVersion": version}, corr, cause, at)

    def _eligibility_released_event(self, certificate: EligibilityCertificate, reservation_id: str, order_intent_id: str, reason: str, version: int, corr: str, cause: str | None, at: datetime) -> EventEnvelope:
        return _envelope("EligibilityUsageReleased", certificate.eligibilityCertificateId, version, {"usageReservationId": reservation_id, "eligibilityCertificateId": certificate.eligibilityCertificateId, "travelerId": certificate.travelerId, "orderIntentId": order_intent_id, "releaseReason": reason, "annualUsageReserved": certificate.annualUsageReserved, "annualUsageConfirmed": certificate.annualUsageConfirmed, "releasedAt": rfc3339_utc(at), "aggregateVersion": version}, corr, cause, at)

    def _fact_recorded_event(self, fact: PurchaseLimitFact, corr: str, cause: str | None, at: datetime) -> EventEnvelope:
        payload = {k: v for k, v in fact.to_json().items() if k != "status"}; payload.update({"factStatus": "RECORDED", "recordedAt": rfc3339_utc(at), "aggregateVersion": fact.version})
        return _envelope("PurchaseLimitFactRecorded", LEDGER_AGGREGATE_ID, fact.version, payload, corr, cause, at, fact.purchaseLimitFactId)

    def _fact_confirmed_event(self, fact: PurchaseLimitFact, journey_order_id: str, corr: str, cause: str | None, at: datetime) -> EventEnvelope:
        return _envelope("PurchaseLimitFactConfirmed", LEDGER_AGGREGATE_ID, fact.version, {"purchaseLimitFactId": fact.purchaseLimitFactId, "journeyOrderId": journey_order_id, "orderIntentId": fact.orderIntentId, "factStatus": "CONFIRMED", "limitPolicyVersion": fact.limitPolicyVersion, "confirmedAt": rfc3339_utc(at), "aggregateVersion": fact.version}, corr, cause, at, fact.purchaseLimitFactId)

    def _fact_released_event(self, fact: PurchaseLimitFact, reason: str, source_event_id: str | None, corr: str, cause: str | None, at: datetime) -> EventEnvelope:
        payload = {"purchaseLimitFactId": fact.purchaseLimitFactId, "orderIntentId": fact.orderIntentId, "releaseReason": reason, "factStatus": "RELEASED", "limitPolicyVersion": fact.limitPolicyVersion, "releasedAt": rfc3339_utc(at), "aggregateVersion": fact.version}
        if source_event_id: payload["sourceEventId"] = source_event_id
        return _envelope("PurchaseLimitFactReleased", LEDGER_AGGREGATE_ID, fact.version, payload, corr, cause, at, fact.purchaseLimitFactId)

    def _fact_missed_event(self, fact: PurchaseLimitFact, ttl_bucket: str, monitor_run_id: str, corr: str, cause: str | None, at: datetime) -> EventEnvelope:
        payload = {"purchaseLimitFactId": fact.purchaseLimitFactId, "ttlBucket": ttl_bucket, "monitorRunId": monitor_run_id, "factStatus": "MISSED", "limitPolicyVersion": fact.limitPolicyVersion, "missedAt": rfc3339_utc(at), "aggregateVersion": fact.version}
        return _envelope("PurchaseLimitFactMissed", LEDGER_AGGREGATE_ID, fact.version, payload, corr, cause, at, fact.purchaseLimitFactId)

    def _fact_failed_event(self, fact: PurchaseLimitFact, failure_code: str, detection_run_id: str, corr: str, cause: str | None, at: datetime) -> EventEnvelope:
        payload = {"purchaseLimitFactId": fact.purchaseLimitFactId, "failureCode": failure_code, "detectionRunId": detection_run_id, "factStatus": "FAILED", "limitPolicyVersion": fact.limitPolicyVersion, "failedAt": rfc3339_utc(at), "aggregateVersion": fact.version}
        return _envelope("PurchaseLimitFactFailed", LEDGER_AGGREGATE_ID, fact.version, payload, corr, cause, at, fact.purchaseLimitFactId)

    def handle_traveler_snapshot_updated(self, envelope: EventEnvelope, stream: str) -> None:
        with self.transaction():
            if not self.store.mark_processed(envelope.eventId, stream):
                return
            payload = dict(envelope.payload)
            traveler_id = str(payload.get("travelerId") or "")
            if traveler_id:
                self.store.save_traveler_snapshot(traveler_id, payload)


    def handle_journey_order_event(self, envelope: EventEnvelope, stream: str = "events:journey-order") -> None:
        with self.transaction():
            if not self.store.mark_processed(envelope.eventId, stream):
                return
            payload = dict(envelope.payload)
            if envelope.eventType == "JourneyOrderCancelled":
                order_id = str(payload.get("orderId") or "")
                if order_id:
                    self.store.release_active_tickets_for_order(order_id)
                return
            if envelope.eventType != "JourneyOrderCreated":
                return
            order_id = str(payload.get("orderId") or "")
            account_id = str(payload.get("accountId") or "")
            segment_refs = tuple(str(item) for item in payload.get("segmentRefs") or () if str(item))
            traveler_ids = self._traveler_ids_from_refs(payload.get("travelerRefs") or ())
            pre_order = self.store.find_reserved_pre_order_check_for_order_event(account_id, traveler_ids, segment_refs)
            if not order_id or pre_order is None:
                return
            body = dict(pre_order.get("body") or {})
            departure_date = str(body.get("journeyDate") or "")
            if not departure_date:
                return
            for fact_id in pre_order.get("purchaseLimitFactIds") or ():
                fact = self.store.get_fact(str(fact_id))
                document_number = self.store.find_cached_document_for_traveler(fact.travelerId, now_utc())
                if document_number is None:
                    continue
                for segment_ref in fact.segmentRefs:
                    self.store.save_active_ticket(ActiveTicket(document_number, segment_ref, departure_date, order_id))

    def handle_risk_alert_raised(self, envelope: EventEnvelope, stream: str = "events:risk-compliance") -> None:
        with self.transaction():
            if not self.store.mark_processed(envelope.eventId, stream):
                return
            if envelope.eventType != "RiskAlertRaised":
                return
            payload = dict(envelope.payload)
            order_id = str(payload.get("orderId") or "")
            if not order_id:
                return
            reason = self._risk_alert_reason(payload)
            at = _parse_dt(str(payload.get("raisedAt") or "")) or now_utc()
            for ticket in self.store.list_active_tickets_for_order(order_id):
                self.store.add_blacklist_entry(BlacklistEntry(ticket.documentNumber, BlacklistType.FRAUD_FLAGGED, reason, at))

    @staticmethod
    def _traveler_ids_from_refs(traveler_refs: Any) -> tuple[str, ...]:
        traveler_ids: list[str] = []
        for traveler in traveler_refs:
            if isinstance(traveler, str):
                traveler_ids.append(traveler)
            elif isinstance(traveler, Mapping) and traveler.get("travelerId"):
                traveler_ids.append(str(traveler["travelerId"]))
        return tuple(traveler_ids)

    @staticmethod
    def _risk_alert_reason(payload: Mapping[str, Any]) -> str:
        triggered_rules = payload.get("triggeredRules")
        if isinstance(triggered_rules, list) and triggered_rules:
            rule_ids = [str(rule.get("ruleId") or rule.get("id")) for rule in triggered_rules if isinstance(rule, Mapping) and (rule.get("ruleId") or rule.get("id"))]
            if rule_ids:
                return "risk alert: " + ",".join(rule_ids)
        verdict = str(payload.get("verdict") or "").strip()
        return f"risk alert: {verdict}" if verdict else "risk alert"
