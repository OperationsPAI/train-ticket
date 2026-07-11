from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from datetime import UTC, datetime
import json
from typing import Any

from train_ticket_platform.storage import OutboxAppender, ProcessedEventsGuard, SnapshotRepository

from identity_verification.application.service import InMemoryStore, NotFoundError
from identity_verification.domain import ActiveTicket, BlacklistEntry, BlacklistType, CredentialRecord, CredentialStatus, EligibilityCertificate, CertificateStatus, PurchaseLimitFact, SimOutcome, VerificationCase, VerificationStatus


def _dt(value: datetime | None) -> str | None:
    if value is None: return None
    return (value if value.tzinfo else value.replace(tzinfo=UTC)).astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


def _parse_dt(value: str | None) -> datetime | None:
    if not value: return None
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


def _json(data: Mapping[str, Any] | str) -> Mapping[str, Any]:
    return json.loads(data) if isinstance(data, str) else data


def blacklist_entry_to_json(entry: BlacklistEntry) -> dict[str, Any]:
    return {"documentNumber": entry.documentNumber, "blacklistType": entry.blacklistType.value, "reason": entry.reason, "effectiveFrom": _dt(entry.effectiveFrom), "effectiveUntil": _dt(entry.effectiveUntil)}


def blacklist_entry_from_json(data: Mapping[str, Any] | str) -> BlacklistEntry:
    d = _json(data)
    return BlacklistEntry(str(d["documentNumber"]), BlacklistType(str(d["blacklistType"])), str(d.get("reason") or ""), _parse_dt(str(d["effectiveFrom"])) or datetime.now(UTC), _parse_dt(d.get("effectiveUntil")))


def ticket_to_json(ticket: ActiveTicket) -> dict[str, Any]:
    return {"documentNumber": ticket.documentNumber, "segmentRef": ticket.segmentRef, "departureDate": ticket.departureDate, "orderId": ticket.orderId, "status": ticket.status}


def ticket_from_json(data: Mapping[str, Any] | str) -> ActiveTicket:
    d = _json(data)
    return ActiveTicket(str(d["documentNumber"]), str(d["segmentRef"]), str(d["departureDate"]), str(d["orderId"]), str(d.get("status") or "ACTIVE"))


def credential_to_json(c: CredentialRecord) -> dict[str, Any]:
    return {"credentialRecordId": c.credentialRecordId, "travelerId": c.travelerId, "profileSnapshotVersion": c.profileSnapshotVersion, "documentType": c.documentType, "maskedDocumentNo": c.maskedDocumentNo, "documentHash": c.documentHash, "materialFingerprint": c.materialFingerprint, "canonicalNameHash": c.canonicalNameHash, "birthDateHash": c.birthDateHash, "evidenceHash": c.evidenceHash, "status": c.status.value, "validUntil": _dt(c.validUntil), "identityClusterId": c.identityClusterId, "verifiedByCaseId": c.verifiedByCaseId, "createdAt": _dt(c.createdAt), "updatedAt": _dt(c.updatedAt)}


def credential_from_json(data: Mapping[str, Any] | str, version: int = 0) -> CredentialRecord:
    d = _json(data)
    return CredentialRecord(str(d["credentialRecordId"]), str(d["travelerId"]), str(d["profileSnapshotVersion"]), str(d["documentType"]), str(d["maskedDocumentNo"]), str(d["documentHash"]), str(d["materialFingerprint"]), str(d["canonicalNameHash"]), d.get("birthDateHash"), d.get("evidenceHash"), CredentialStatus(str(d["status"])), _parse_dt(d.get("validUntil")), d.get("identityClusterId"), d.get("verifiedByCaseId"), _parse_dt(str(d["createdAt"])) or datetime.now(UTC), _parse_dt(str(d["updatedAt"])) or datetime.now(UTC), version)


def case_to_json(c: VerificationCase) -> dict[str, Any]:
    return {"verificationCaseId": c.verificationCaseId, "travelerId": c.travelerId, "credentialRecordId": c.credentialRecordId, "purpose": c.purpose, "status": c.status.value, "materialFingerprint": c.materialFingerprint, "simPolicyVersion": c.simPolicyVersion, "simOutcome": c.simOutcome.value if c.simOutcome else None, "simResultRef": c.simResultRef, "reasonCode": c.reasonCode, "validFrom": _dt(c.validFrom), "validUntil": _dt(c.validUntil), "submittedAt": _dt(c.submittedAt), "completedAt": _dt(c.completedAt), "createdAt": _dt(c.createdAt), "aggregateVersion": c.version}


def case_from_json(data: Mapping[str, Any] | str, version: int = 0) -> VerificationCase:
    d = _json(data)
    return VerificationCase(str(d["verificationCaseId"]), str(d["travelerId"]), str(d["credentialRecordId"]), str(d["purpose"]), VerificationStatus(str(d["status"])), str(d["materialFingerprint"]), str(d["simPolicyVersion"]), SimOutcome(str(d["simOutcome"])) if d.get("simOutcome") else None, d.get("simResultRef"), d.get("reasonCode"), _parse_dt(d.get("validFrom")), _parse_dt(d.get("validUntil")), _parse_dt(d.get("submittedAt")), _parse_dt(d.get("completedAt")), _parse_dt(str(d["createdAt"])) or datetime.now(UTC), int(d.get("aggregateVersion", version)))


def certificate_to_json(c: EligibilityCertificate) -> dict[str, Any]:
    return {"eligibilityCertificateId": c.eligibilityCertificateId, "travelerId": c.travelerId, "credentialRecordId": c.credentialRecordId, "identityClusterId": c.identityClusterId, "eligibilityType": c.eligibilityType, "status": c.status.value, "validFrom": _dt(c.validFrom), "validUntil": _dt(c.validUntil), "policyYear": c.policyYear, "policyVersion": c.policyVersion, "annualUsageLimit": c.annualUsageLimit, "annualUsageReserved": c.annualUsageReserved, "annualUsageConfirmed": c.annualUsageConfirmed, "applicableProductCodes": list(c.applicableProductCodes), "certificateHash": c.certificateHash, "evidenceHash": c.evidenceHash, "reasonCode": c.reasonCode, "createdAt": _dt(c.createdAt), "updatedAt": _dt(c.updatedAt), "aggregateVersion": c.version}


def certificate_from_json(data: Mapping[str, Any] | str, version: int = 0) -> EligibilityCertificate:
    d = _json(data)
    return EligibilityCertificate(str(d["eligibilityCertificateId"]), str(d["travelerId"]), d.get("credentialRecordId"), d.get("identityClusterId"), str(d["eligibilityType"]), CertificateStatus(str(d["status"])), _parse_dt(str(d["validFrom"])) or datetime.now(UTC), _parse_dt(str(d["validUntil"])) or datetime.now(UTC), str(d["policyYear"]), str(d["policyVersion"]), int(d["annualUsageLimit"]), int(d.get("annualUsageReserved", 0)), int(d.get("annualUsageConfirmed", 0)), tuple(str(x) for x in d.get("applicableProductCodes", ())), str(d["certificateHash"]), str(d["evidenceHash"]), d.get("reasonCode"), _parse_dt(str(d["createdAt"])) or datetime.now(UTC), _parse_dt(str(d["updatedAt"])) or datetime.now(UTC), int(d.get("aggregateVersion", version)))


def fact_to_json(f: PurchaseLimitFact) -> dict[str, Any]:
    data = {"purchaseLimitFactId": f.purchaseLimitFactId, "scopeType": f.scopeType, "scopeRef": f.scopeRef, "travelerId": f.travelerId, "orderIntentId": f.orderIntentId, "journeyDate": f.journeyDate, "productCode": f.productCode, "segmentRefs": list(f.segmentRefs), "limitPolicyVersion": f.limitPolicyVersion, "status": f.status, "recordedAt": _dt(f.recordedAt), "aggregateVersion": f.version}
    if f.journeyOrderId: data["journeyOrderId"] = f.journeyOrderId
    if f.releaseReason: data["releaseReason"] = f.releaseReason
    if f.sourceEventId: data["sourceEventId"] = f.sourceEventId
    if f.ttlBucket: data["ttlBucket"] = f.ttlBucket
    if f.monitorRunId: data["monitorRunId"] = f.monitorRunId
    if f.failureCode: data["failureCode"] = f.failureCode
    if f.detectionRunId: data["detectionRunId"] = f.detectionRunId
    return data


def fact_from_json(data: Mapping[str, Any] | str, version: int = 0) -> PurchaseLimitFact:
    d = _json(data)
    return PurchaseLimitFact(str(d["purchaseLimitFactId"]), str(d["scopeType"]), str(d["scopeRef"]), str(d["travelerId"]), str(d["orderIntentId"]), str(d["journeyDate"]), str(d["productCode"]), tuple(str(x) for x in d.get("segmentRefs", ())), str(d["limitPolicyVersion"]), str(d["status"]), _parse_dt(str(d["recordedAt"])) or datetime.now(UTC), int(d.get("aggregateVersion", version)), d.get("journeyOrderId"), d.get("releaseReason"), d.get("sourceEventId"), d.get("ttlBucket"), d.get("monitorRunId"), d.get("failureCode"), d.get("detectionRunId"))


@dataclass
class _UnitOfWorkState:
    connection: Any | None = None
    loaded_versions: dict[tuple[str, str], int] | None = None
    def __post_init__(self) -> None:
        if self.loaded_versions is None: self.loaded_versions = {}


_UNIT_OF_WORK: ContextVar[_UnitOfWorkState | None] = ContextVar("identity_verification_uow", default=None)


class PostgresIdentityVerificationStore(InMemoryStore):
    def __init__(self, pool: Any, outbox: OutboxAppender | None = None) -> None:
        self._pool = pool; self._outbox = outbox or OutboxAppender()
        self._credentials = SnapshotRepository("credential_record_snapshots")
        self._cases = SnapshotRepository("verification_case_snapshots")
        self._certificates = SnapshotRepository("eligibility_certificate_snapshots")
        self._facts = SnapshotRepository("purchase_limit_fact_snapshots")
        self._pre_orders = SnapshotRepository("pre_order_check_snapshots")
        self._blacklist = SnapshotRepository("identity_blacklist_snapshots")
        self._active_tickets = SnapshotRepository("active_ticket_snapshots")
        self._verification_cache = SnapshotRepository("verification_cache_snapshots")
        self._processed = ProcessedEventsGuard()

    @contextmanager
    def transaction(self):
        state = _UNIT_OF_WORK.get()
        if state is not None and state.connection is not None:
            yield state.connection; return
        with self._pool.connection() as conn:
            with conn.transaction():
                token = _UNIT_OF_WORK.set(_UnitOfWorkState(connection=conn))
                try: yield conn
                finally: _UNIT_OF_WORK.reset(token)

    @contextmanager
    def unit_of_work(self):
        if _UNIT_OF_WORK.get() is not None:
            yield; return
        token = _UNIT_OF_WORK.set(_UnitOfWorkState())
        try: yield
        finally: _UNIT_OF_WORK.reset(token)

    def _with_conn(self, func: Callable[[Any], Any]) -> Any:
        state = _UNIT_OF_WORK.get()
        if state is not None and state.connection is not None: return func(state.connection)
        with self._pool.connection() as conn: return func(conn)

    def _remember(self, aggregate: str, aggregate_id: str, version: int) -> None:
        state = _UNIT_OF_WORK.get()
        if state and state.loaded_versions is not None: state.loaded_versions[(aggregate, aggregate_id)] = version

    def _take(self, aggregate: str, aggregate_id: str) -> int | None:
        state = _UNIT_OF_WORK.get()
        if state is None or state.loaded_versions is None: return None
        return state.loaded_versions.pop((aggregate, aggregate_id), None)

    def get_credential(self, credential_id: str) -> CredentialRecord:
        def read(conn: Any) -> CredentialRecord:
            snap = self._credentials.get(conn, credential_id)
            if snap is None: raise NotFoundError(f"credential not found: {credential_id}")
            version, data = snap; self._remember("credential", credential_id, version); return credential_from_json(data, version)
        return self._with_conn(read)

    def save_credential(self, credential: CredentialRecord) -> None:
        self._with_conn(lambda conn: self._credentials.save(conn, credential.credentialRecordId, credential_to_json(credential), self._take("credential", credential.credentialRecordId)))

    def find_credential_duplicate(self, traveler_id: str, document_type: str, document_hash: str, material_fingerprint: str, snapshot_version: str) -> CredentialRecord | None:
        def read(conn: Any) -> CredentialRecord | None:
            row = conn.execute("SELECT id, version, data FROM credential_record_snapshots WHERE data->>'travelerId'=%s AND data->>'documentType'=%s AND data->>'documentHash'=%s AND data->>'materialFingerprint'=%s AND data->>'profileSnapshotVersion'=%s LIMIT 1", (traveler_id, document_type, document_hash, material_fingerprint, snapshot_version)).fetchone()
            if not row: return None
            self._remember("credential", str(row[0]), int(row[1])); return credential_from_json(row[2], int(row[1]))
        return self._with_conn(read)

    def credentials_for_traveler(self, traveler_id: str) -> tuple[CredentialRecord, ...]:
        def read(conn: Any) -> tuple[CredentialRecord, ...]:
            rows = conn.execute("SELECT id, version, data FROM credential_record_snapshots WHERE data->>'travelerId'=%s ORDER BY data->>'updatedAt' DESC", (traveler_id,)).fetchall()
            return tuple(credential_from_json(row[2], int(row[1])) for row in rows)
        return self._with_conn(read)

    def get_case(self, case_id: str) -> VerificationCase:
        def read(conn: Any) -> VerificationCase:
            snap = self._cases.get(conn, case_id)
            if snap is None: raise NotFoundError(f"verification case not found: {case_id}")
            version, data = snap; self._remember("case", case_id, version); return case_from_json(data, version)
        return self._with_conn(read)

    def save_case(self, case: VerificationCase) -> None:
        self._with_conn(lambda conn: self._cases.save(conn, case.verificationCaseId, case_to_json(case), self._take("case", case.verificationCaseId)))

    def find_case_duplicate(self, traveler_id: str, credential_id: str, purpose: str, material_fingerprint: str, policy: str) -> VerificationCase | None:
        def read(conn: Any) -> VerificationCase | None:
            row = conn.execute("SELECT id, version, data FROM verification_case_snapshots WHERE data->>'travelerId'=%s AND data->>'credentialRecordId'=%s AND data->>'purpose'=%s AND data->>'materialFingerprint'=%s AND data->>'simPolicyVersion'=%s LIMIT 1", (traveler_id, credential_id, purpose, material_fingerprint, policy)).fetchone()
            if not row: return None
            self._remember("case", str(row[0]), int(row[1])); return case_from_json(row[2], int(row[1]))
        return self._with_conn(read)

    def find_case_by_credential(self, credential_id: str) -> VerificationCase | None:
        def read(conn: Any) -> VerificationCase | None:
            row = conn.execute("SELECT id, version, data FROM verification_case_snapshots WHERE data->>'credentialRecordId'=%s ORDER BY data->>'createdAt' DESC LIMIT 1", (credential_id,)).fetchone()
            if not row: return None
            self._remember("case", str(row[0]), int(row[1])); return case_from_json(row[2], int(row[1]))
        return self._with_conn(read)

    def get_certificate(self, certificate_id: str) -> EligibilityCertificate:
        def read(conn: Any) -> EligibilityCertificate:
            snap = self._certificates.get(conn, certificate_id)
            if snap is None: raise NotFoundError(f"eligibility certificate not found: {certificate_id}")
            version, data = snap; self._remember("certificate", certificate_id, version); return certificate_from_json(data, version)
        return self._with_conn(read)

    def save_certificate(self, certificate: EligibilityCertificate) -> None:
        self._with_conn(lambda conn: self._certificates.save(conn, certificate.eligibilityCertificateId, certificate_to_json(certificate), self._take("certificate", certificate.eligibilityCertificateId)))

    def find_certificate_duplicate(self, traveler_id: str, eligibility_type: str, certificate_hash: str, policy_year: str, policy_version: str) -> EligibilityCertificate | None:
        def read(conn: Any) -> EligibilityCertificate | None:
            row = conn.execute("SELECT id, version, data FROM eligibility_certificate_snapshots WHERE data->>'travelerId'=%s AND data->>'eligibilityType'=%s AND data->>'certificateHash'=%s AND data->>'policyYear'=%s AND data->>'policyVersion'=%s LIMIT 1", (traveler_id, eligibility_type, certificate_hash, policy_year, policy_version)).fetchone()
            if not row: return None
            self._remember("certificate", str(row[0]), int(row[1])); return certificate_from_json(row[2], int(row[1]))
        return self._with_conn(read)

    def query_certificates(self, traveler_id: str, eligibility_type: str | None, journey_date: str, product_code: str | None, limit: int, offset: int) -> tuple[tuple[EligibilityCertificate, ...], int]:
        def read(conn: Any) -> tuple[tuple[EligibilityCertificate, ...], int]:
            params: list[Any] = [traveler_id, journey_date, journey_date]
            where = "data->>'travelerId'=%s AND data->>'status'='ACTIVE' AND substring(data->>'validFrom' from 1 for 10) <= %s AND substring(data->>'validUntil' from 1 for 10) >= %s"
            if eligibility_type: where += " AND data->>'eligibilityType'=%s"; params.append(eligibility_type)
            if product_code: where += " AND (data->'applicableProductCodes') ? %s"; params.append(product_code)
            total = int(conn.execute(f"SELECT count(*) FROM eligibility_certificate_snapshots WHERE {where} AND ((data->>'annualUsageReserved')::numeric + (data->>'annualUsageConfirmed')::numeric) < (data->>'annualUsageLimit')::numeric", tuple(params)).fetchone()[0])
            rows = conn.execute(f"SELECT id, version, data FROM eligibility_certificate_snapshots WHERE {where} AND ((data->>'annualUsageReserved')::numeric + (data->>'annualUsageConfirmed')::numeric) < (data->>'annualUsageLimit')::numeric ORDER BY data->>'createdAt' LIMIT %s OFFSET %s", tuple(params + [limit, offset])).fetchall()
            certificates = []
            for row in rows:
                # Loaded aggregates must register their version in the unit of
                # work, or a later save is treated as an INSERT and trips OCC.
                self._remember("certificate", str(row[0]), int(row[1]))
                certificates.append(certificate_from_json(row[2], int(row[1])))
            return tuple(certificates), total
        return self._with_conn(read)

    def save_fact(self, fact: PurchaseLimitFact) -> None:
        self._with_conn(lambda conn: self._facts.save(conn, fact.purchaseLimitFactId, fact_to_json(fact), self._take("fact", fact.purchaseLimitFactId)))

    def find_fact_duplicate(self, scope_type: str, scope_ref: str, journey_date: str, product_code: str, order_intent_id: str) -> PurchaseLimitFact | None:
        def read(conn: Any) -> PurchaseLimitFact | None:
            row = conn.execute("SELECT id, version, data FROM purchase_limit_fact_snapshots WHERE data->>'scopeType'=%s AND data->>'scopeRef'=%s AND data->>'journeyDate'=%s AND data->>'productCode'=%s AND data->>'orderIntentId'=%s LIMIT 1", (scope_type, scope_ref, journey_date, product_code, order_intent_id)).fetchone()
            if not row: return None
            self._remember("fact", str(row[0]), int(row[1])); return fact_from_json(row[2], int(row[1]))
        return self._with_conn(read)

    def get_fact(self, fact_id: str) -> PurchaseLimitFact:
        def read(conn: Any) -> PurchaseLimitFact:
            snap = self._facts.get(conn, fact_id)
            if snap is None: raise NotFoundError(f"purchase-limit fact not found: {fact_id}")
            version, data = snap; self._remember("fact", fact_id, version); return fact_from_json(data, version)
        return self._with_conn(read)

    def save_pre_order_check(self, record: Mapping[str, Any]) -> None:
        record_id = str(record["preOrderCheckId"])
        self._with_conn(lambda conn: self._pre_orders.save(conn, record_id, dict(record), self._take("pre-order", record_id)))

    def get_pre_order_check(self, pre_order_check_id: str) -> dict[str, Any]:
        def read(conn: Any) -> dict[str, Any]:
            snap = self._pre_orders.get(conn, pre_order_check_id)
            if snap is None: raise NotFoundError(f"pre-order check not found: {pre_order_check_id}")
            version, data = snap; self._remember("pre-order", pre_order_check_id, version); return dict(data)
        return self._with_conn(read)

    def find_pre_order_check_duplicate(self, material_hash: str) -> dict[str, Any] | None:
        def read(conn: Any) -> dict[str, Any] | None:
            row = conn.execute("SELECT id, version, data FROM pre_order_check_snapshots WHERE data->>'materialHash'=%s LIMIT 1", (material_hash,)).fetchone()
            if not row: return None
            self._remember("pre-order", str(row[0]), int(row[1])); return dict(row[2])
        return self._with_conn(read)

    def add_blacklist_entry(self, entry: BlacklistEntry) -> None:
        record_id = f"blk-{entry.documentNumber}-{entry.blacklistType.value}"
        def write(conn: Any) -> None:
            snap = self._blacklist.get(conn, record_id)
            expected = int(snap[0]) if snap is not None else None
            self._blacklist.save(conn, record_id, blacklist_entry_to_json(entry), expected)
        self._with_conn(write)

    def list_blacklist_entries(self, document_number: str) -> tuple[BlacklistEntry, ...]:
        def read(conn: Any) -> tuple[BlacklistEntry, ...]:
            rows = conn.execute("SELECT id, version, data FROM identity_blacklist_snapshots WHERE data->>'documentNumber'=%s", (document_number,)).fetchall()
            return tuple(blacklist_entry_from_json(row[2]) for row in rows)
        return self._with_conn(read)

    def find_active_ticket(self, document_number: str, segment_ref: str, departure_date: str) -> ActiveTicket | None:
        ticket_id = f"act-{document_number}-{segment_ref}-{departure_date}"
        def read(conn: Any) -> ActiveTicket | None:
            snap = self._active_tickets.get(conn, ticket_id)
            if snap is None: return None
            version, data = snap; self._remember("active-ticket", ticket_id, version)
            ticket = ticket_from_json(data)
            return ticket if ticket.status == "ACTIVE" else None
        return self._with_conn(read)

    def save_active_ticket(self, ticket: ActiveTicket) -> None:
        ticket_id = f"act-{ticket.documentNumber}-{ticket.segmentRef}-{ticket.departureDate}"
        def write(conn: Any) -> None:
            expected = self._take("active-ticket", ticket_id)
            if expected is None:
                snap = self._active_tickets.get(conn, ticket_id)
                expected = int(snap[0]) if snap is not None else None
            self._active_tickets.save(conn, ticket_id, ticket_to_json(ticket), expected)
        self._with_conn(write)

    def release_active_tickets_for_order(self, order_id: str) -> None:
        def write(conn: Any) -> None:
            rows = conn.execute("SELECT id, version, data FROM active_ticket_snapshots WHERE data->>'orderId'=%s", (order_id,)).fetchall()
            for row in rows:
                data = dict(row[2]); data["status"] = "CANCELLED"
                self._active_tickets.save(conn, str(row[0]), data, int(row[1]))
        self._with_conn(write)

    def get_cached_verification(self, traveler_id: str, document_number: str, at: datetime) -> tuple[datetime, datetime] | None:
        cache_id = f"vcache-{traveler_id}-{document_number}"
        def read(conn: Any) -> tuple[datetime, datetime] | None:
            snap = self._verification_cache.get(conn, cache_id)
            if snap is None: return None
            version, data = snap; self._remember("verification-cache", cache_id, version)
            verified_at = _parse_dt(str(data["verifiedAt"])) or datetime.now(UTC)
            expires_at = _parse_dt(str(data["expiresAt"])) or datetime.now(UTC)
            return (verified_at, expires_at) if expires_at > at else None
        return self._with_conn(read)

    def save_cached_verification(self, traveler_id: str, document_number: str, verified_at: datetime, expires_at: datetime) -> None:
        cache_id = f"vcache-{traveler_id}-{document_number}"
        data = {"travelerId": traveler_id, "documentNumber": document_number, "verifiedAt": _dt(verified_at), "expiresAt": _dt(expires_at)}
        def write(conn: Any) -> None:
            expected = self._take("verification-cache", cache_id)
            if expected is None:
                snap = self._verification_cache.get(conn, cache_id)
                expected = int(snap[0]) if snap is not None else None
            self._verification_cache.save(conn, cache_id, data, expected)
        self._with_conn(write)

    def save_traveler_snapshot(self, traveler_id: str, data: Mapping[str, Any]) -> None:
        def write(conn: Any) -> None:
            conn.execute("INSERT INTO traveler_snapshot_links(traveler_id, snapshot_version, data) VALUES (%s,%s,%s) ON CONFLICT (traveler_id) DO UPDATE SET snapshot_version=EXCLUDED.snapshot_version, data=EXCLUDED.data, updated_at=now()", (traveler_id, str(data.get("snapshotVersion") or data.get("profileSnapshotVersion") or ""), json.dumps(dict(data))))
        self._with_conn(write)

    def append_outbox(self, envelopes: Iterable[Any]) -> None:
        def write(conn: Any) -> None:
            for envelope in envelopes: self._outbox.append(conn, envelope)
        self._with_conn(write)

    def mark_processed(self, event_id: str, stream: str | None = None) -> bool:
        return bool(self._with_conn(lambda conn: self._processed.try_mark_processed(conn, event_id, stream)))
