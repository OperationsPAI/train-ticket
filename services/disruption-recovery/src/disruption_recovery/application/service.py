from __future__ import annotations

from contextlib import nullcontext, nullcontext
from dataclasses import dataclass, replace
from datetime import datetime, timedelta
from hashlib import sha256
from uuid import UUID
from typing import Any, Mapping

from train_ticket_platform.events import EventEnvelope, rfc3339_utc

from disruption_recovery.domain import (
    ActorRef,
    DomainError,
    PreconditionFailed,
    Evidence,
    ExecutionTarget,
    Incident,
    RecoveryCase,
    RecoveryCaseStatus,
    RecoveryOption,
    RecoveryOptionType,
    build_option_set,
    now_utc,
    require_text,
)
from disruption_recovery.ids import prefixed_uuid7

PRODUCER = "disruption-recovery"
TERMINAL_STATUSES = {RecoveryCaseStatus.RECOVERED, RecoveryCaseStatus.DECLINED, RecoveryCaseStatus.FAILED, RecoveryCaseStatus.CLOSED}


class NotFoundError(KeyError):
    pass


PreconditionFailedError = PreconditionFailed


@dataclass(frozen=True, slots=True)
class DisruptionReport:
    disruptionId: str
    disruptionType: str
    scheduledServiceRef: str | None
    segmentRef: str | None
    serviceDate: str
    evidence: Evidence
    affectedOrderIds: tuple[str, ...]
    reportedBy: ActorRef
    reportedAt: datetime
    incidentId: str

    def to_json(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "disruptionId": self.disruptionId,
            "disruptionType": self.disruptionType,
            "serviceDate": self.serviceDate,
            "evidence": self.evidence.to_json(),
            "affectedOrderIds": list(self.affectedOrderIds),
            "reportedBy": self.reportedBy.to_json(),
            "reportedAt": rfc3339_utc(self.reportedAt),
            "incidentId": self.incidentId,
        }
        if self.scheduledServiceRef:
            data["scheduledServiceRef"] = self.scheduledServiceRef
        if self.segmentRef:
            data["segmentRef"] = self.segmentRef
        return data


class InMemoryStore:
    def __init__(self) -> None:
        self.incidents: dict[str, Incident] = {}
        self.incident_merge_index: dict[str, str] = {}
        self.cases: dict[str, RecoveryCase] = {}
        self.case_index: dict[tuple[str, str], str] = {}
        self.refund_execution_index: dict[str, str] = {}
        self._outbox: list[EventEnvelope] = []
        self.processed_events: set[str] = set()

    def transaction(self) -> Any:
        return nullcontext()

    def append_outbox(self, envelopes: tuple[EventEnvelope, ...]) -> None:
        self._outbox.extend(envelopes)

    def take_outbox(self) -> tuple[EventEnvelope, ...]:
        items = tuple(self._outbox)
        self._outbox.clear()
        return items

    def mark_processed(self, event_id: str, stream: str | None = None) -> bool:
        if event_id in self.processed_events:
            return False
        self.processed_events.add(event_id)
        return True

    def get_incident(self, incident_id: str) -> Incident:
        try:
            return self.incidents[incident_id]
        except KeyError as exc:
            raise NotFoundError(f"incident not found: {incident_id}") from exc

    def save_incident(self, incident: Incident) -> None:
        self.incidents[incident.incidentId] = incident
        if incident.scheduledServiceRef:
            self.incident_merge_index[f"{incident.scheduledServiceRef}\u001f{incident.serviceDate}"] = incident.incidentId

    def find_incident_by_merge_key(self, scheduled_service_ref: str, service_date: str) -> Incident | None:
        incident_id = self.incident_merge_index.get(f"{scheduled_service_ref}\u001f{service_date}")
        return self.incidents.get(incident_id) if incident_id else None

    def get_case(self, case_id: str) -> RecoveryCase:
        try:
            return self.cases[case_id]
        except KeyError as exc:
            raise NotFoundError(f"recovery case not found: {case_id}") from exc

    def save_case(self, case: RecoveryCase) -> None:
        self.cases[case.caseId] = case
        self.case_index[(case.incidentId, case.journeyOrderId)] = case.caseId
        if case.execution and case.execution.externalRef and case.execution.target is ExecutionTarget.POST_SALES:
            self.refund_execution_index[case.execution.externalRef] = case.caseId

    def find_case(self, incident_id: str, order_id: str) -> RecoveryCase | None:
        case_id = self.case_index.get((incident_id, order_id))
        return self.cases.get(case_id) if case_id else None

    def list_cases(self, incident_id: str, status: str | None, limit: int, offset: int) -> tuple[tuple[RecoveryCase, ...], int]:
        items = [case for case in self.cases.values() if case.incidentId == incident_id and (status is None or case.status.value == status)]
        items.sort(key=lambda case: case.openedAt)
        return tuple(items[offset: offset + limit]), len(items)

    def find_case_by_post_sales_case(self, post_sales_case_id: str) -> RecoveryCase | None:
        case_id = self.refund_execution_index.get(post_sales_case_id)
        if case_id:
            return self.cases.get(case_id)
        for case in self.cases.values():
            if case.execution and case.execution.externalRef == post_sales_case_id:
                return case
        return None


class DownstreamPort:
    def open_refund_case(self, body: Mapping[str, Any], idempotency_key: str, correlation_id: str) -> Mapping[str, Any]:
        raise NotImplementedError

    def issue_compensation(self, body: Mapping[str, Any], idempotency_key: str, correlation_id: str) -> Mapping[str, Any]:
        raise NotImplementedError


class NoopDownstream(DownstreamPort):
    def open_refund_case(self, body: Mapping[str, Any], idempotency_key: str, correlation_id: str) -> Mapping[str, Any]:
        return {"caseId": f"psc-{sha256(idempotency_key.encode()).hexdigest()[:32]}", "status": "OPENED"}

    def issue_compensation(self, body: Mapping[str, Any], idempotency_key: str, correlation_id: str) -> Mapping[str, Any]:
        return {"benefitId": "ben-" + sha256(idempotency_key.encode()).hexdigest()[:32], "status": "ISSUED"}


def _event_id(event_type: str, aggregate_id: str, version: int) -> str:
    # Deterministic identity, UUID-v7-shaped per the envelope contract:
    # sha256 material folded into a UUID with the version/variant nibbles
    # stamped (legacy-acl ids.py precedent).
    digest = bytearray(sha256(f"{PRODUCER}:{event_type}:{aggregate_id}:{version}".encode("utf-8")).digest()[:16])
    digest[6] = (digest[6] & 0x0F) | 0x70
    digest[8] = (digest[8] & 0x3F) | 0x80
    return f"evt-{UUID(bytes=bytes(digest))}"


def _envelope(event_type: str, aggregate_id: str, version: int, payload: Mapping[str, Any], correlation_id: str, causation_id: str, occurred_at: datetime) -> EventEnvelope:
    # Wire ids are canonical corr-/cmd- prefixed UUID v7 (java-kit consumers
    # validate strictly; a raw request id poisoned the woken post-sales
    # subscription at the wave-17 gate).
    from train_ticket_platform.events import canonical_causation_id, canonical_correlation_id
    return EventEnvelope(eventId=_event_id(event_type, aggregate_id, version), eventType=event_type, occurredAt=occurred_at, correlationId=canonical_correlation_id(correlation_id), causationId=canonical_causation_id(causation_id), producer=PRODUCER, schemaVersion=1, payload=payload)


class DisruptionRecoveryService:
    def __init__(self, store: Any, downstream: DownstreamPort | None = None) -> None:
        self.store = store
        self.downstream = downstream or NoopDownstream()

    def transaction(self) -> Any:
        transaction = getattr(self.store, "transaction", None)
        return transaction() if callable(transaction) else nullcontext()

    def _append(self, events: list[EventEnvelope]) -> None:
        append = getattr(self.store, "append_outbox", None)
        if callable(append):
            append(tuple(events))

    def report_disruption(self, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        evidence = Evidence(**dict(data.get("evidence") or {}))
        reported_by = ActorRef(**dict(data.get("reportedBy") or {}))
        disruption_type = require_text(str(data.get("disruptionType") or ""), "disruptionType")
        service_date = require_text(str(data.get("serviceDate") or ""), "serviceDate")
        scheduled = str(data.get("scheduledServiceRef") or "").strip() or None
        segment = str(data.get("segmentRef") or "").strip() or None
        if not scheduled and not segment:
            raise DomainError("scheduledServiceRef or segmentRef is required")
        affected = tuple(dict.fromkeys(str(item).strip() for item in data.get("affectedOrderIds") or [] if str(item).strip()))
        if not affected:
            raise DomainError("affectedOrderIds must be non-empty")
        disruption_id = prefixed_uuid7("drp")
        incident_opened = False
        if scheduled:
            incident = self.store.find_incident_by_merge_key(scheduled, service_date)
        else:
            incident = None
        if incident is None:
            incident = Incident.open(prefixed_uuid7("inc"), disruption_type, scheduled, segment, service_date, evidence.evidenceRef, affected, at)
            incident_opened = True
        else:
            incident = incident.merge(evidence.evidenceRef, segment, affected, at)
        self.store.save_incident(incident)
        report = DisruptionReport(disruption_id, disruption_type, scheduled, segment, service_date, evidence, affected, reported_by, at, incident.incidentId)
        events = [_envelope("DisruptionReported", disruption_id, 1, report.to_json() | {"reportedAt": rfc3339_utc(at)}, correlation_id, causation_id, at)]
        if incident_opened:
            events.append(_envelope("IncidentOpened", incident.incidentId, incident.version + 1, incident.to_json() | {"disruptionId": disruption_id, "affectedOrderIds": list(affected)}, correlation_id, causation_id, at))
        cases: list[RecoveryCase] = []
        for order_id in affected:
            existing = self.store.find_case(incident.incidentId, order_id)
            if existing is not None:
                cases.append(existing)
                continue
            affected_scope = {"journeyOrderId": order_id, "scheduledServiceRef": scheduled, "segmentRef": segment, "serviceDate": service_date, "disruptionType": disruption_type, "evidenceRef": evidence.evidenceRef}
            account_id = str(data.get("accountId") or "").strip()
            if account_id:
                affected_scope["accountId"] = account_id
            case = RecoveryCase(prefixed_uuid7("rcv"), incident.incidentId, order_id, affected_scope, RecoveryCaseStatus.OPENED, at, at)
            events.append(_envelope("RecoveryCaseOpened", case.caseId, 1, {"caseId": case.caseId, "incidentId": incident.incidentId, "disruptionId": disruption_id, "journeyOrderId": order_id, "affectedScope": dict(case.affectedScope), "openedAt": rfc3339_utc(at), "status": "OPENED"}, correlation_id, causation_id, at))
            case = case.assess(at)
            wait_only = order_id.endswith("0") or str(data.get("autoRecovery") or "").upper() == "WAIT"
            option_set = build_option_set(case.caseId, order_id, segment, at, prefixed_uuid7("ros"), tuple(prefixed_uuid7("rop") for _ in range(4)), wait_only)
            refund_scope = data.get("refundScope")
            if isinstance(refund_scope, Mapping) and not wait_only:
                option_set = replace(option_set, options=tuple(self._with_refund_scope(option, refund_scope) for option in option_set.options))
            case = case.attach_options(option_set, at)
            options_status = RecoveryCaseStatus.AWAITING_USER_CHOICE if option_set.requiresUserChoice else RecoveryCaseStatus.OPTIONS_GENERATED
            events.append(_envelope("RecoveryOptionsGenerated", case.caseId, 2, {"caseId": case.caseId, "incidentId": incident.incidentId, "journeyOrderId": order_id, "optionSetId": option_set.optionSetId, "options": [o.to_json() for o in option_set.options], "requiresUserChoice": option_set.requiresUserChoice, "generatedAt": rfc3339_utc(at), "status": options_status.value} | ({"expiresAt": rfc3339_utc(option_set.expiresAt)} if option_set.expiresAt else {}), correlation_id, causation_id, at))
            if not option_set.requiresUserChoice:
                case, wait_option = case.auto_wait(ActorRef("SYSTEM", "disruption-recovery"), prefixed_uuid7("rex"), at)
                events.extend(self._selection_events(case, wait_option, ActorRef("SYSTEM", "disruption-recovery"), correlation_id, causation_id, at))
                events.append(self._completed_event(case, wait_option, correlation_id, causation_id, at))
            self.store.save_case(case)
            cases.append(case)
        events.append(_envelope("ServiceAlertPublished", incident.incidentId, incident.version + 2, {"serviceAlertId": prefixed_uuid7("sal"), "incidentId": incident.incidentId, "disruptionType": disruption_type, "serviceDate": service_date, "audience": "AFFECTED_ORDERS", "affectedOrderIds": list(affected), "messageSummary": evidence.summary, "publishedAt": rfc3339_utc(at)} | ({"scheduledServiceRef": scheduled} if scheduled else {}) | ({"segmentRef": segment} if segment else {}), correlation_id, causation_id, at))
        self._append(events)
        return {"disruption": report.to_json(), "incident": incident.to_json(), "recoveryCases": [case.to_json() for case in cases]}

    def select_option(self, case_id: str, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        case = self.store.get_case(case_id)
        actor = ActorRef(**dict(data.get("selectedBy") or {}))
        option_id = require_text(str(data.get("optionId") or ""), "optionId")
        option = case.optionSet.option_by_id(option_id) if case.optionSet else None
        if option is None:
            raise PreconditionFailedError("option set is missing")
        idem = self._downstream_key(case.caseId, option)
        case, option = case.select(option_id, actor, prefixed_uuid7("rex"), idem, at)
        events = self._selection_events(case, option, actor, correlation_id, causation_id, at)
        if option.optionType is RecoveryOptionType.WAIT:
            case = case.complete(option, None, at)
            events.append(self._completed_event(case, option, correlation_id, causation_id, at))
        elif option.optionType is RecoveryOptionType.REFUND:
            external = self._execute_refund(case, option, correlation_id)
            case = self._with_external(case, external)
        elif option.optionType is RecoveryOptionType.COMPENSATION:
            external = self._execute_compensation(case, option, correlation_id)
            case = self._with_external(case, external).complete(option, external, at)
            events.append(self._completed_event(case, option, correlation_id, causation_id, at))
        self.store.save_case(case)
        self._append(events)
        return case.to_json()

    def resolve_manual(self, case_id: str, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        case = self.store.get_case(case_id)
        target = RecoveryCaseStatus(str(data.get("outcome") or data.get("status") or ""))
        case = case.resolve_manual(target, at, str(data.get("manualRef") or f"manual:{case_id}"))
        option = case.selected_option() or RecoveryOption("rop-manual", RecoveryOptionType.MANUAL, "Manual review", "Manual review", ExecutionTarget.MANUAL_QUEUE)
        event = self._completed_event(case, option, correlation_id, causation_id, at) if target is RecoveryCaseStatus.RECOVERED else self._failed_event(case, str(data.get("reason") or "Manual review failed"), correlation_id, causation_id, at)
        self.store.save_case(case)
        self._append([event])
        return case.to_json()

    def close_case(self, case_id: str, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        case = self.store.get_case(case_id)
        previous = case.status
        closed_by = ActorRef(**dict(data.get("closedBy") or {}))
        reason = require_text(str(data.get("closeReason") or ""), "closeReason")
        case = case.close(at)
        self.store.save_case(case)
        self._append([_envelope("RecoveryCaseClosed", case.caseId, case.version + 1, {"caseId": case.caseId, "incidentId": case.incidentId, "journeyOrderId": case.journeyOrderId, "previousStatus": previous.value, "closedBy": closed_by.to_json(), "closeReason": reason, "closedAt": rfc3339_utc(at), "status": "CLOSED"}, correlation_id, causation_id, at)])
        return case.to_json()

    def handle_post_sales_applied(self, envelope: EventEnvelope, stream: str | None = None) -> bool:
        if envelope.eventType != "PostSalesApplied":
            return False
        # The dedup claim must commit atomically with the case mutation and
        # the outbox append: a crash after a standalone claim would ack-skip
        # the upstream event forever and lose refund convergence.
        transaction = getattr(self.store, "transaction", None)
        context = transaction() if callable(transaction) else nullcontext()
        with context:
            mark = getattr(self.store, "mark_processed", None)
            if callable(mark) and not mark(envelope.eventId, stream):
                return True
            payload = dict(envelope.payload)
            post_sales_case_id = str(payload.get("caseId") or "")
            case = self.store.find_case_by_post_sales_case(post_sales_case_id)
            if case is None or case.status in TERMINAL_STATUSES:
                return True
            option = case.selected_option()
            if option is None:
                return True
            at = now_utc()
            case = case.complete(option, post_sales_case_id, at)
            self.store.save_case(case)
            self._append([self._completed_event(case, option, envelope.correlationId, envelope.eventId, at)])
            return True

    def _with_refund_scope(self, option: RecoveryOption, refund_scope: Mapping[str, Any]) -> RecoveryOption:
        if option.optionType is not RecoveryOptionType.REFUND:
            return option
        refund = dict(option.refund or {})
        refund["scope"] = {
            "orderItemRefs": list(refund_scope.get("orderItemRefs") or []),
            "segmentRefs": list(refund_scope.get("segmentRefs") or []),
            "travelerRefs": list(refund_scope.get("travelerRefs") or []),
            "entitlementRefs": list(refund_scope.get("entitlementRefs") or []),
        }
        return replace(option, refund=refund)

    def _selection_events(self, case: RecoveryCase, option: RecoveryOption, actor: ActorRef, correlation_id: str, causation_id: str, at: datetime) -> list[EventEnvelope]:
        assert case.optionSet is not None and case.execution is not None
        semantic_status = RecoveryCaseStatus.MANUAL_REVIEW if option.optionType is RecoveryOptionType.MANUAL else RecoveryCaseStatus.EXECUTING_RECOVERY
        selected = _envelope("RecoveryOptionSelected", case.caseId, case.version + 3, {"caseId": case.caseId, "incidentId": case.incidentId, "journeyOrderId": case.journeyOrderId, "optionSetId": case.optionSet.optionSetId, "optionId": option.optionId, "optionType": option.optionType.value, "selectedBy": actor.to_json(), "selectedAt": rfc3339_utc(at), "status": semantic_status.value}, correlation_id, causation_id, at)
        payload = {"caseId": case.caseId, "incidentId": case.incidentId, "journeyOrderId": case.journeyOrderId, "optionId": option.optionId, "optionType": option.optionType.value, "executionId": case.execution.executionId, "executionTarget": case.execution.target.value, "startedAt": rfc3339_utc(at), "status": semantic_status.value}
        if case.execution.idempotencyKey:
            payload["idempotencyKey"] = case.execution.idempotencyKey
        downstream_request = self._downstream_request_summary(case, option)
        if downstream_request:
            payload["downstreamRequest"] = downstream_request
        started = _envelope("RecoveryExecutionStarted", case.caseId, case.version + 4, payload, correlation_id, causation_id, at)
        return [selected, started]

    def _downstream_request_summary(self, case: RecoveryCase, option: RecoveryOption) -> dict[str, Any] | None:
        if option.optionType is RecoveryOptionType.REFUND:
            refund = dict(option.refund or {})
            return {"caseType": "REFUND", "reasonCode": refund.get("reasonCode") or "DISRUPTION_REFUND", "idempotencyKey": case.execution.idempotencyKey if case.execution else None}
        if option.optionType is RecoveryOptionType.COMPENSATION:
            comp = dict(option.compensation or {})
            return {"issuanceSource": "DISRUPTION_COMP", "benefitType": comp.get("benefitType") or "COMPENSATION_CREDIT", "amount": comp.get("amount") or {"currency": "CNY", "minorUnits": 1000}, "idempotencyKey": case.execution.idempotencyKey if case.execution else None}
        return None

    def _completed_event(self, case: RecoveryCase, option: RecoveryOption, correlation_id: str, causation_id: str, at: datetime) -> EventEnvelope:
        assert case.execution is not None
        return _envelope("RecoveryCompleted", case.caseId, case.version + 5, {"caseId": case.caseId, "incidentId": case.incidentId, "journeyOrderId": case.journeyOrderId, "optionId": option.optionId, "optionType": option.optionType.value, "executionId": case.execution.executionId, "completedAt": rfc3339_utc(at), "status": "RECOVERED"} | ({"externalRef": case.execution.externalRef} if case.execution.externalRef else {}), correlation_id, causation_id, at)

    def _failed_event(self, case: RecoveryCase, reason: str, correlation_id: str, causation_id: str, at: datetime) -> EventEnvelope:
        return _envelope("RecoveryFailed", case.caseId, case.version + 5, {"caseId": case.caseId, "incidentId": case.incidentId, "journeyOrderId": case.journeyOrderId, "optionId": case.selectedOptionId, "executionId": case.execution.executionId if case.execution else None, "failedAt": rfc3339_utc(at), "reason": reason, "nextStatus": case.status.value}, correlation_id, causation_id, at)

    def _downstream_key(self, case_id: str, option: RecoveryOption) -> str:
        # Downstream HTTP idempotency middleware only accepts UUID-v7 keys;
        # deterministic material folded into a version-stamped UUID keeps
        # replays stable (legacy-acl / waitlist ruling — composite string
        # keys were rejected live at the wave-17 gate).
        digest = bytearray(sha256(f"{PRODUCER}:downstream:{case_id}:{option.optionType.value}".encode("utf-8")).digest()[:16])
        digest[6] = (digest[6] & 0x0F) | 0x70
        digest[8] = (digest[8] & 0x3F) | 0x80
        return str(UUID(bytes=bytes(digest)))

    def _with_external(self, case: RecoveryCase, external_ref: str) -> RecoveryCase:
        from dataclasses import replace
        if case.execution is None:
            return case
        return replace(case, execution=replace(case.execution, externalRef=external_ref))

    def _execute_refund(self, case: RecoveryCase, option: RecoveryOption, correlation_id: str) -> str:
        assert case.execution and case.execution.idempotencyKey
        refund = dict(option.refund or {})
        scope = dict(refund.get("scope") or {})
        scope = {"orderItemRefs": list(scope.get("orderItemRefs") or []), "segmentRefs": list(scope.get("segmentRefs") or []), "travelerRefs": list(scope.get("travelerRefs") or []), "entitlementRefs": list(scope.get("entitlementRefs") or [])}
        body = {"journeyOrderId": case.journeyOrderId, "caseType": "REFUND", "scope": scope, "reasonCode": refund.get("reasonCode") or "DISRUPTION_REFUND", "actorRef": "disruption-recovery"}
        response = self.downstream.open_refund_case(body, case.execution.idempotencyKey, correlation_id)
        return str(response.get("caseId") or response.get("postSalesCaseId") or "")

    def _execute_compensation(self, case: RecoveryCase, option: RecoveryOption, correlation_id: str) -> str:
        assert case.execution and case.execution.idempotencyKey
        comp = dict(option.compensation or {})
        now = now_utc()
        body = {"accountId": str(case.affectedScope.get("accountId") or case.journeyOrderId), "benefitType": comp.get("benefitType") or "COMPENSATION_CREDIT", "balanceType": comp.get("balanceType") or "PROMOTION_CREDIT", "amount": comp.get("amount") or {"currency": "CNY", "minorUnits": 1000}, "issuanceSource": "DISRUPTION_COMP", "caseId": case.caseId, "applicableScope": {"scopeType": "ANY_TRIP", "currency": "CNY"}, "redemptionRule": {"singleUse": False, "requiresReservation": False}, "revocationRule": {}, "validFrom": rfc3339_utc(now), "validUntil": comp.get("validUntil") or rfc3339_utc(now + timedelta(days=30)), "businessReason": {"reasonType": "DISRUPTION_COMP", "reasonCode": comp.get("reasonCode") or "DISRUPTION_COMP", "referenceType": "RECOVERY_CASE", "referenceId": case.caseId}}
        response = self.downstream.issue_compensation(body, case.execution.idempotencyKey, correlation_id)
        return str(response.get("benefitId") or "")
