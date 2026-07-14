from __future__ import annotations

from contextlib import nullcontext
from dataclasses import dataclass, replace
from datetime import UTC, datetime, timedelta
from hashlib import sha256
from uuid import UUID
from typing import Any, Mapping

from train_ticket_platform.events import EventEnvelope, rfc3339_utc
from train_ticket_platform.messaging import HandlerResult

from disruption_recovery.domain import (
    ActorRef,
    DomainError,
    DISRUPTION_TYPES,
    AffectedBooking,
    AlternativeRoute,
    CompensationCalculator,
    CompensationDeliveryMethod,
    Disruption,
    DisruptionClassifier,
    PreconditionFailed,
    Evidence,
    ExecutionTarget,
    Incident,
    MassDisruptionProcessor,
    RecoveryCase,
    RecoveryCaseStatus,
    RecoveryOption,
    RecoveryOptionType,
    ReroutingDecision,
    ServiceAlert,
    SeatClass,
    build_option_set,
    normalize_disruption_type,
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
        self.segment_order_index: dict[str, set[str]] = {}
        self.service_alerts: dict[str, ServiceAlert] = {}
        self.pending_segment_signals: dict[str, EventEnvelope] = {}

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

    def index_order_segments(self, order_id: str, segment_refs: tuple[str, ...]) -> None:
        for segment_ref in segment_refs:
            self.segment_order_index.setdefault(segment_ref, set()).add(order_id)

    def find_orders_by_segment_ref(self, segment_ref: str) -> tuple[str, ...]:
        return tuple(sorted(self.segment_order_index.get(segment_ref, ())))

    def save_pending_segment_signal(self, envelope: EventEnvelope) -> None:
        self.pending_segment_signals[envelope.eventId] = envelope
        self.processed_events.discard(envelope.eventId)

    def take_pending_segment_signals(self, segment_refs: tuple[str, ...]) -> tuple[EventEnvelope, ...]:
        wanted = set(segment_refs)
        selected = tuple(
            signal for signal in self.pending_segment_signals.values()
            if str(signal.payload.get("segmentRef") or "").strip() in wanted
        )
        for signal in selected:
            self.pending_segment_signals.pop(signal.eventId, None)
        return selected

    def save_service_alert(self, alert: ServiceAlert) -> None:
        self.service_alerts[alert.serviceAlertId] = alert

    def get_service_alert(self, service_alert_id: str) -> ServiceAlert:
        try:
            return self.service_alerts[service_alert_id]
        except KeyError as exc:
            raise NotFoundError(f"service alert not found: {service_alert_id}") from exc

    def list_service_alerts(self, incident_id: str | None, order_id: str | None, limit: int, offset: int) -> tuple[tuple[ServiceAlert, ...], int]:
        items = [
            alert for alert in self.service_alerts.values()
            if (incident_id is None or alert.incidentId == incident_id) and (order_id is None or order_id in alert.affectedOrderIds)
        ]
        items.sort(key=lambda alert: alert.publishedAt, reverse=True)
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

    def reaccommodate_connection(self, connection_id: str, body: Mapping[str, Any], idempotency_key: str, correlation_id: str) -> Mapping[str, Any]:
        raise NotImplementedError


class NoopDownstream(DownstreamPort):
    def open_refund_case(self, body: Mapping[str, Any], idempotency_key: str, correlation_id: str) -> Mapping[str, Any]:
        return {"caseId": f"psc-{sha256(idempotency_key.encode()).hexdigest()[:32]}", "status": "OPENED"}

    def issue_compensation(self, body: Mapping[str, Any], idempotency_key: str, correlation_id: str) -> Mapping[str, Any]:
        return {"benefitId": "ben-" + sha256(idempotency_key.encode()).hexdigest()[:32], "status": "ISSUED"}

    def reaccommodate_connection(self, connection_id: str, body: Mapping[str, Any], idempotency_key: str, correlation_id: str) -> Mapping[str, Any]:
        replacement_id = "con-" + sha256(idempotency_key.encode()).hexdigest()[:32]
        return {"connection": {"connectionId": connection_id, "status": "RECOVERED", "replacementConnectionId": replacement_id}, "replacementConnection": {"connectionId": replacement_id}, "caseId": body.get("caseId")}


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
    from train_ticket_platform.events import canonical_correlation_id
    causation = causation_id or ""
    if causation and not causation.startswith(("cmd-", "evt-")):
        # java-kit consumers require a cmd-/evt- prefixed causation id; the
        # HTTP layer hands us the raw request id.
        causation = f"cmd-{causation}"
    return EventEnvelope(eventId=_event_id(event_type, aggregate_id, version), eventType=event_type, occurredAt=occurred_at, correlationId=canonical_correlation_id(correlation_id), causationId=causation or None, producer=PRODUCER, schemaVersion=1, payload=payload)


def _coerce_replacement_window(data: Mapping[str, Any], generated_at: datetime) -> dict[str, Any]:
    window = dict(data.get("replacementWindow") or {})
    planned = str(window.get("plannedArrivalAt") or rfc3339_utc(generated_at + timedelta(minutes=15)))
    departure = str(window.get("nextDepartureAt") or rfc3339_utc(generated_at + timedelta(minutes=75)))
    cutoff = str(window.get("nextCutoffAt") or departure)
    source = str(window.get("source") or "SYSTEM").strip().upper()
    if source not in {"OPERATIONS", "SYSTEM"}:
        source = "SYSTEM"
    return {"plannedArrivalAt": planned, "nextDepartureAt": departure, "nextCutoffAt": cutoff, "source": source}


def _reaccommodation_from_report(data: Mapping[str, Any], generated_at: datetime) -> dict[str, Any] | None:
    if str(data.get("disruptionType") or "") != "MISSED_CONNECTION":
        return None
    evidence = dict(data.get("evidence") or {})
    reported_by = dict(data.get("reportedBy") or {})
    if evidence.get("sourceSystem") != "TRANSFER_MANAGEMENT" or reported_by.get("actorType") != "SYSTEM":
        return None
    connection_id = str(data.get("connectionId") or evidence.get("evidenceRef") or "").strip()
    if not connection_id:
        return None
    return {"connectionId": connection_id, "replacementWindow": _coerce_replacement_window(data, generated_at)}


def _source_system_for_signal(envelope: EventEnvelope) -> str:
    return "PROVIDER_INTEGRATION" if envelope.producer == "provider-integration" or envelope.eventType.startswith("Provider") else "FULFILLMENT"


def _stream_for_signal(envelope: EventEnvelope) -> str:
    return "events:provider-integration" if _source_system_for_signal(envelope) == "PROVIDER_INTEGRATION" else "events:fulfillment"


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

    def _append_declared_event(self, events: list[EventEnvelope], report: DisruptionReport, data: Mapping[str, Any], correlation_id: str, causation_id: str, at: datetime) -> Disruption:
        normalized_type = normalize_disruption_type(report.disruptionType)
        delay_minutes = _int_or_none(data.get("delayMinutes"))
        severity = DisruptionClassifier.classify(normalized_type, delay_minutes)
        estimated_resolution = _parse_datetime(data.get("estimatedResolution"))
        disruption = Disruption(report.disruptionId, report.segmentRef or report.scheduledServiceRef or "unknown-segment", normalized_type, severity, at, estimated_resolution, len(report.affectedOrderIds), delay_minutes)
        events.append(_envelope("DisruptionDeclared", report.disruptionId, 2, disruption.to_json(), correlation_id, causation_id, at))
        return disruption

    def report_disruption(self, data: Mapping[str, Any], correlation_id: str, causation_id: str) -> dict[str, Any]:
        at = now_utc()
        evidence = Evidence(**dict(data.get("evidence") or {}))
        reported_by = ActorRef(**dict(data.get("reportedBy") or {}))
        disruption_type = require_text(str(data.get("disruptionType") or ""), "disruptionType")
        if disruption_type not in DISRUPTION_TYPES:
            raise DomainError("disruptionType is invalid")
        service_date = require_text(str(data.get("serviceDate") or ""), "serviceDate")
        scheduled = str(data.get("scheduledServiceRef") or "").strip() or None
        segment = str(data.get("segmentRef") or "").strip() or None
        if not scheduled and not segment:
            raise DomainError("scheduledServiceRef or segmentRef is required")
        explicit_affected = tuple(dict.fromkeys(str(item).strip() for item in data.get("affectedOrderIds") or [] if str(item).strip()))
        resolved_affected = self._resolve_segment_orders(segment) if segment else ()
        affected = tuple(dict.fromkeys((*explicit_affected, *resolved_affected)))
        if not affected:
            raise DomainError("affectedOrderIds must be non-empty or segmentRef must resolve to affected orders")
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
        disruption = self._append_declared_event(events, report, data, correlation_id, causation_id, at)
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
            reaccommodation = _reaccommodation_from_report(data, at)
            wait_only = reaccommodation is None and (order_id.endswith("0") or str(data.get("autoRecovery") or "").upper() == "WAIT")
            option_set = build_option_set(case.caseId, order_id, segment, at, prefixed_uuid7("ros"), tuple(prefixed_uuid7("rop") for _ in range(4)), wait_only, reaccommodation)
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
        mass_recovery = self._process_mass_disruption(disruption, data, affected, correlation_id, causation_id, at)
        events.extend(mass_recovery["events"])
        alert_payload = {"serviceAlertId": prefixed_uuid7("sal"), "incidentId": incident.incidentId, "disruptionType": disruption_type, "serviceDate": service_date, "audience": "AFFECTED_ORDERS", "affectedOrderIds": list(affected), "messageSummary": evidence.summary, "publishedAt": rfc3339_utc(at)} | ({"scheduledServiceRef": scheduled} if scheduled else {}) | ({"segmentRef": segment} if segment else {})
        self._save_service_alert(alert_payload)
        events.append(_envelope("ServiceAlertPublished", incident.incidentId, incident.version + 2, alert_payload, correlation_id, causation_id, at))
        self._append(events)
        response = {"disruption": report.to_json() | {"classification": disruption.to_json()}, "incident": incident.to_json(), "recoveryCases": [case.to_json() for case in cases]}
        if mass_recovery["summary"] is not None:
            response["massRecovery"] = mass_recovery["summary"]
        return response

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
        elif option.optionType is RecoveryOptionType.REACCOMMODATION:
            external = self._execute_reaccommodation(case, option, correlation_id)
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

    def _resolve_segment_orders(self, segment_ref: str | None) -> tuple[str, ...]:
        if not segment_ref:
            return ()
        resolver = getattr(self.store, "find_orders_by_segment_ref", None)
        if not callable(resolver):
            return ()
        return tuple(dict.fromkeys(str(item).strip() for item in resolver(segment_ref) if str(item).strip()))

    def _save_service_alert(self, payload: Mapping[str, Any]) -> None:
        save = getattr(self.store, "save_service_alert", None)
        if callable(save):
            save(ServiceAlert.from_payload(payload))

    def _save_pending_segment_signal(self, envelope: EventEnvelope) -> None:
        save = getattr(self.store, "save_pending_segment_signal", None)
        if callable(save):
            save(envelope)

    def _take_pending_segment_signals(self, segment_refs: tuple[str, ...]) -> tuple[EventEnvelope, ...]:
        take = getattr(self.store, "take_pending_segment_signals", None)
        if not callable(take):
            return ()
        return tuple(take(segment_refs))

    def handle_journey_order_created(self, envelope: EventEnvelope, stream: str | None = None) -> bool:
        if envelope.eventType != "JourneyOrderCreated":
            return False
        with self.transaction():
            mark = getattr(self.store, "mark_processed", None)
            if callable(mark) and not mark(envelope.eventId, stream):
                return True
            payload = dict(envelope.payload)
            order_id = str(payload.get("orderId") or "").strip()
            if not order_id:
                return True
            segment_refs = tuple(dict.fromkeys(str(item).strip() for item in payload.get("segmentRefs") or [] if str(item).strip()))
            index = getattr(self.store, "index_order_segments", None)
            if callable(index):
                index(order_id, segment_refs)
            mark = getattr(self.store, "mark_processed", None)
            for pending in self._take_pending_segment_signals(segment_refs):
                self._handle_pending_segment_signal(pending, _source_system_for_signal(pending), _stream_for_signal(pending))
                if callable(mark):
                    mark(pending.eventId, _stream_for_signal(pending))
            return True

    def handle_service_alert_published(self, envelope: EventEnvelope, stream: str | None = None) -> bool:
        if envelope.eventType != "ServiceAlertPublished":
            return False
        with self.transaction():
            mark = getattr(self.store, "mark_processed", None)
            if callable(mark) and not mark(envelope.eventId, stream):
                return True
            self._save_service_alert(dict(envelope.payload))
            return True

    def handle_fulfillment_signal(self, envelope: EventEnvelope, stream: str | None = None) -> bool:
        if envelope.eventType not in {"SegmentDelayed", "SegmentCancelled"}:
            return False
        return self._handle_segment_signal(envelope, "FULFILLMENT", stream)

    def handle_provider_signal(self, envelope: EventEnvelope, stream: str | None = None) -> bool:
        if envelope.eventType not in {"ProviderSegmentDelayed", "ProviderSegmentCancelled", "SegmentDelayed", "SegmentCancelled"}:
            return False
        return self._handle_segment_signal(envelope, "PROVIDER_INTEGRATION", stream)

    def _handle_segment_signal(self, envelope: EventEnvelope, source_system: str, stream: str | None) -> bool:
        with self.transaction():
            data = self._report_from_segment_signal(envelope, source_system)
            if data is None:
                self._save_pending_segment_signal(envelope)
                return HandlerResult.transient_error("segmentRef has no indexed affected orders yet")
            mark = getattr(self.store, "mark_processed", None)
            if callable(mark) and not mark(envelope.eventId, stream):
                return True
            self.report_disruption(data, envelope.correlationId, envelope.eventId)
            return True

    def _handle_pending_segment_signal(self, envelope: EventEnvelope, source_system: str, stream: str | None) -> None:
        data = self._report_from_segment_signal(envelope, source_system)
        if data is not None:
            self.report_disruption(data, envelope.correlationId, envelope.eventId)

    def _report_from_segment_signal(self, envelope: EventEnvelope, source_system: str) -> dict[str, Any] | None:
        payload = dict(envelope.payload)
        segment_ref = str(payload.get("segmentRef") or "").strip()
        scheduled = str(payload.get("scheduledServiceRef") or "").strip() or None
        service_date = str(payload.get("serviceDate") or "").strip()
        if not segment_ref or not service_date:
            return None
        affected = self._resolve_segment_orders(segment_ref)
        if not affected:
            return None
        disruption_type = "CANCELLATION" if "Cancelled" in envelope.eventType else "DELAY"
        occurred_at = payload.get("observedAt") or payload.get("cancelledAt") or payload.get("estimatedArrivalAt") or envelope.occurredAt
        summary = "Segment cancelled" if disruption_type == "CANCELLATION" else "Segment delayed"
        data: dict[str, Any] = {
            "disruptionType": disruption_type,
            "segmentRef": segment_ref,
            "serviceDate": service_date,
            "evidence": {
                "evidenceRef": envelope.eventId,
                "sourceSystem": source_system,
                "sourceRecordId": envelope.eventId,
                "summary": summary,
                "occurredAt": rfc3339_utc(_parse_datetime(occurred_at) or now_utc()),
            },
            "affectedOrderIds": list(affected),
            "reportedBy": {"actorType": "SYSTEM", "actorId": source_system.lower().replace("_", "-")},
        }
        if scheduled:
            data["scheduledServiceRef"] = scheduled
        delay_minutes = _int_or_none(payload.get("delayMinutes"))
        if delay_minutes is not None:
            data["delayMinutes"] = delay_minutes
        return data

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

    def _process_mass_disruption(self, disruption: Disruption, data: Mapping[str, Any], affected_order_ids: tuple[str, ...], correlation_id: str, causation_id: str, at: datetime) -> dict[str, Any]:
        batch_requested = _bool_flag(data.get("processBatches")) or len(affected_order_ids) > MassDisruptionProcessor.BATCH_SIZE
        if disruption.type not in {normalize_disruption_type("CANCELLATION"), normalize_disruption_type("FORCE_MAJEURE")} and disruption.severity.value != "SEVERE" and not batch_requested:
            return {"events": [], "summary": None}
        bookings = self._affected_bookings(data, affected_order_ids, disruption.segmentRef, at)
        alternatives = self._alternative_routes(data)
        processor = MassDisruptionProcessor()
        batches, outcomes, progress = processor.process(bookings, alternatives, lambda: prefixed_uuid7("dbt"))
        events: list[EventEnvelope] = [
            _envelope("MassDisruptionDetected", disruption.disruptionId, 3, {"disruptionId": disruption.disruptionId, "affectedCount": len(bookings)}, correlation_id, causation_id, at),
            _envelope("BatchProcessingStarted", disruption.disruptionId, 4, {"disruptionId": disruption.disruptionId, "batchCount": len(batches), "batchSize": MassDisruptionProcessor.BATCH_SIZE, "slaMinutes": 30, "startedAt": rfc3339_utc(at)}, correlation_id, causation_id, at),
        ]
        compensation_events: list[EventEnvelope] = []
        outcomes_by_order = {outcome.orderId: outcome for outcome in outcomes}
        calculator = CompensationCalculator()
        for index, batch in enumerate(batches, start=1):
            batch_outcomes = tuple(outcomes_by_order[booking.orderId] for booking in batch.bookings)
            events.append(_envelope("AlternativesSearched", batch.batchId, 1, {"disruptionId": disruption.disruptionId, "batchId": batch.batchId, "searchedCount": len(batch.bookings), "alternativeCount": len(alternatives)}, correlation_id, causation_id, at))
            for outcome in batch_outcomes:
                payload = {"disruptionId": disruption.disruptionId, "batchId": batch.batchId} | outcome.to_json()
                events.append(_envelope("RebookingDecided", f"{batch.batchId}:{outcome.orderId}", 1, payload, correlation_id, causation_id, at))
                if outcome.decision is ReroutingDecision.AUTO_REBOOK and outcome.suggestion is not None:
                    events.append(_envelope("PassengerRebooked", f"{disruption.disruptionId}:{outcome.orderId}", 1, {"disruptionId": disruption.disruptionId, "orderId": outcome.orderId, "newSegmentRef": outcome.suggestion.newSegmentRef, "score": round(outcome.suggestion.score, 2)}, correlation_id, causation_id, at))
            events.append(_envelope("BatchProcessingCompleted", batch.batchId, 2, {"disruptionId": disruption.disruptionId, **batch.to_json(), "completedAt": rfc3339_utc(at), "batchNumber": index}, correlation_id, causation_id, at))
            for booking in batch.bookings:
                award = calculator.calculate(booking.ticketPriceMinorUnits, disruption.delayMinutes, disruption.type, booking.currency, _delivery_method(data))
                if award.totalMinorUnits <= 0:
                    continue
                compensation_events.append(_envelope("CompensationIssued", f"{disruption.disruptionId}:{booking.orderId}", 1, {"disruptionId": disruption.disruptionId, "orderId": booking.orderId, "amount": award.to_json()["total"], "refund": award.to_json()["refund"], "compensation": award.to_json()["compensation"], "method": award.deliveryMethod.value, "issuedBy": rfc3339_utc(at + timedelta(days=7))}, correlation_id, causation_id, at))
        events.extend(compensation_events)
        events.append(_envelope("DisruptionResolved", disruption.disruptionId, 5, {"disruptionId": disruption.disruptionId, "totalProcessed": progress.processed, "progress": progress.to_json(), "resolvedAt": rfc3339_utc(at)}, correlation_id, causation_id, at))
        return {"events": events, "summary": {"batches": [batch.to_json() for batch in batches], "outcomes": [outcome.to_json() for outcome in outcomes], "progress": progress.to_json()}}

    def _affected_bookings(self, data: Mapping[str, Any], affected_order_ids: tuple[str, ...], segment_ref: str, fallback_departure: datetime) -> tuple[AffectedBooking, ...]:
        raw_bookings = data.get("affectedBookings")
        if isinstance(raw_bookings, list) and raw_bookings:
            return tuple(self._booking_from_mapping(item, segment_ref, fallback_departure) for item in raw_bookings if isinstance(item, Mapping))
        return tuple(
            AffectedBooking(
                orderId=order_id,
                travelerId=order_id,
                segmentRef=segment_ref,
                originalDeparture=_parse_datetime(data.get("originalDeparture")) or fallback_departure,
                seatClass=_seat_class(data.get("seatClass")),
                ticketPriceMinorUnits=max(0, int(data.get("ticketPriceMinorUnits") or 0)),
                currency=str(data.get("currency") or "CNY"),
            )
            for order_id in affected_order_ids
        )

    def _booking_from_mapping(self, item: Mapping[str, Any], segment_ref: str, fallback_departure: datetime) -> AffectedBooking:
        price = item.get("ticketPriceMinorUnits")
        if price is None and isinstance(item.get("ticketPrice"), Mapping):
            price = dict(item.get("ticketPrice") or {}).get("minorUnits")
        return AffectedBooking(
            orderId=require_text(str(item.get("orderId") or ""), "affectedBookings.orderId"),
            travelerId=str(item.get("travelerId") or item.get("orderId") or ""),
            segmentRef=str(item.get("segmentRef") or segment_ref),
            origin=str(item.get("origin") or "") or None,
            destination=str(item.get("destination") or "") or None,
            originalDeparture=_parse_datetime(item.get("originalDeparture")) or fallback_departure,
            seatClass=_seat_class(item.get("seatClass")),
            ticketPriceMinorUnits=max(0, int(price or 0)),
            currency=str(item.get("currency") or dict(item.get("ticketPrice") or {}).get("currency") or "CNY"),
        )

    def _alternative_routes(self, data: Mapping[str, Any]) -> tuple[AlternativeRoute, ...]:
        routes: list[AlternativeRoute] = []
        for item in data.get("alternativeRoutes") or []:
            if not isinstance(item, Mapping):
                continue
            departure = _parse_datetime(item.get("departureTime"))
            if departure is None:
                continue
            routes.append(AlternativeRoute(str(item.get("segmentRef") or item.get("newSegmentRef") or ""), departure, _seat_class(item.get("seatClass")), max(0, int(item.get("transfers") or 0)), str(item.get("origin") or "") or None, str(item.get("destination") or "") or None))
        return tuple(routes)

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
        if option.optionType is RecoveryOptionType.REACCOMMODATION:
            reaccommodation = dict(option.reaccommodation or {})
            return {"connectionId": reaccommodation.get("connectionId"), "replacementWindow": dict(reaccommodation.get("replacementWindow") or {}), "idempotencyKey": case.execution.idempotencyKey if case.execution else None}
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
        if option.optionType is RecoveryOptionType.REACCOMMODATION:
            reaccommodation = dict(option.reaccommodation or {})
            material = f"{PRODUCER}:reaccommodation:{case_id}:{option.optionId}:{reaccommodation.get('connectionId')}"
        else:
            material = f"{PRODUCER}:downstream:{case_id}:{option.optionType.value}"
        digest = bytearray(sha256(material.encode("utf-8")).digest()[:16])
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

    def _execute_reaccommodation(self, case: RecoveryCase, option: RecoveryOption, correlation_id: str) -> str:
        assert case.execution and case.execution.idempotencyKey
        reaccommodation = dict(option.reaccommodation or {})
        connection_id = require_text(reaccommodation.get("connectionId"), "reaccommodation.connectionId")
        body = {"caseId": case.caseId, "replacementWindow": dict(reaccommodation.get("replacementWindow") or {})}
        response = self.downstream.reaccommodate_connection(connection_id, body, case.execution.idempotencyKey, correlation_id)
        replacement = dict(response.get("replacementConnection") or {})
        return str(replacement.get("connectionId") or response.get("replacementConnectionId") or connection_id)


def _parse_datetime(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        return (value if value.tzinfo else value.replace(tzinfo=UTC)).astimezone(UTC)
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    return datetime.fromisoformat(text.replace("Z", "+00:00")).astimezone(UTC)


def _int_or_none(value: Any) -> int | None:
    if value is None or str(value).strip() == "":
        return None
    return max(0, int(value))


def _seat_class(value: Any) -> SeatClass:
    text = str(value or "SECOND").strip().upper()
    return SeatClass(text) if text in {item.value for item in SeatClass} else SeatClass.SECOND


def _delivery_method(data: Mapping[str, Any]) -> CompensationDeliveryMethod:
    text = str(data.get("compensationDeliveryMethod") or "POINTS").strip().upper()
    return CompensationDeliveryMethod.CASH if text == "CASH" else CompensationDeliveryMethod.POINTS


def _bool_flag(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() in {"1", "true", "yes", "y"}
