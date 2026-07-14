from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any, Mapping, Protocol

from train_ticket_platform.events import EventEnvelope, envelope_factory, rfc3339_utc
from train_ticket_platform.messaging import EventPublisher

from .application import prefixed_id
from .domain import (
    DetectedPattern,
    PurchaseLimitFact,
    RiskComplianceError,
    RiskEvaluation,
    RiskScoreCalculator,
    RiskSignal,
    RiskVerdict,
    RuleResult,
    ScalperPattern,
    VelocityDimension,
    VelocityRule,
    strongest_verdict,
)

SCHEMA_VERSION = 1
PRODUCER = "risk-compliance"

ACCOUNT_ORDER_RULE = VelocityRule("VELOCITY_ACCOUNT_ORDER", VelocityDimension.ACCOUNT, 3, 5 * 60, RiskVerdict.BLOCK)
ACCOUNT_PAYMENT_RULE = VelocityRule("VELOCITY_ACCOUNT_PAYMENT", VelocityDimension.ACCOUNT, 5, 10 * 60, RiskVerdict.CHALLENGE)
TRAVELER_BOOKING_RULE = VelocityRule("VELOCITY_TRAVELER_BOOKING", VelocityDimension.TRAVELER, 2, 60 * 60, RiskVerdict.BLOCK)
IP_ORDER_RULE = VelocityRule("VELOCITY_IP_ORDER", VelocityDimension.IP, 10, 15 * 60, RiskVerdict.BLOCK)
class EvaluationNotFoundError(KeyError):
    pass


class VelocityCounterPort(Protocol):
    def increment_and_count(self, dimension: VelocityDimension, key: str, occurred_at: datetime, window_seconds: int) -> int: ...


class InMemoryVelocityCounter:
    def __init__(self) -> None:
        self._events: dict[tuple[VelocityDimension, str], list[datetime]] = defaultdict(list)

    def increment_and_count(self, dimension: VelocityDimension, key: str, occurred_at: datetime, window_seconds: int) -> int:
        if not key.strip():
            return 0
        window_start = occurred_at - timedelta(seconds=window_seconds)
        events = [seen_at for seen_at in self._events[(dimension, key)] if window_start <= seen_at <= occurred_at]
        events.append(occurred_at)
        self._events[(dimension, key)] = events
        return len(events)


@dataclass(slots=True)
class OrderHistoryRecord:
    account_id: str
    order_id: str
    origin: str
    destination: str
    traveler_refs: tuple[str, ...]
    departure_date: str
    occurred_at: datetime


@dataclass(slots=True)
class RiskEvaluationRepository:
    _evaluations: dict[str, RiskEvaluation] = field(default_factory=dict)
    _purchase_limit_facts: dict[str, PurchaseLimitFact] = field(default_factory=dict)
    _processed_purchase_limit_fact_keys: set[tuple[str, str]] = field(default_factory=set)
    _account_created_at: dict[str, datetime] = field(default_factory=dict)
    _orders: list[OrderHistoryRecord] = field(default_factory=list)
    _payment_attempts: dict[str, list[datetime]] = field(default_factory=lambda: defaultdict(list))
    _refunds: dict[str, list[datetime]] = field(default_factory=lambda: defaultdict(list))

    def save(self, evaluation: RiskEvaluation) -> None:
        self._evaluations[evaluation.evaluation_id] = evaluation

    def get(self, evaluation_id: str) -> RiskEvaluation:
        try:
            return self._evaluations[evaluation_id]
        except KeyError as exc:
            raise EvaluationNotFoundError(evaluation_id) from exc

    def account_created_at(self, account_id: str) -> datetime | None:
        return self._account_created_at.get(account_id)

    def record_account_registered(self, account_id: str, occurred_at: datetime) -> None:
        self._account_created_at.setdefault(account_id, occurred_at)

    def record_order(self, record: OrderHistoryRecord) -> None:
        if not any(existing.order_id == record.order_id for existing in self._orders):
            self._orders.append(record)

    def orders_for_account_since(self, account_id: str, since: datetime) -> tuple[OrderHistoryRecord, ...]:
        return tuple(order for order in self._orders if order.account_id == account_id and order.occurred_at >= since)

    def record_payment_attempt(self, account_id: str, occurred_at: datetime) -> None:
        self._payment_attempts[account_id].append(occurred_at)

    def payment_attempts_since(self, account_id: str, since: datetime) -> int:
        attempts = [seen_at for seen_at in self._payment_attempts[account_id] if seen_at >= since]
        self._payment_attempts[account_id] = attempts
        return len(attempts)

    def record_refund(self, account_id: str, occurred_at: datetime) -> None:
        self._refunds[account_id].append(occurred_at)

    def try_mark_purchase_limit_fact_processed(self, event_id: str, fact_id: str, event_type: str) -> bool:
        del event_id
        dedup_key = (fact_id, event_type)
        if dedup_key in self._processed_purchase_limit_fact_keys:
            return False
        self._processed_purchase_limit_fact_keys.add(dedup_key)
        return True

    def record_purchase_limit_fact_processed(self, event_id: str, fact_id: str, event_type: str) -> None:
        del event_id
        self._processed_purchase_limit_fact_keys.add((fact_id, event_type))

    def upsert_purchase_limit_fact(self, fact: PurchaseLimitFact) -> None:
        existing = self._purchase_limit_facts.get(fact.fact_id)
        self._purchase_limit_facts[fact.fact_id] = fact if existing is None else existing.merge(fact)

    def active_purchase_limit_facts(self, traveler_refs: tuple[str, ...]) -> tuple[PurchaseLimitFact, ...]:
        traveler_set = {traveler_ref for traveler_ref in traveler_refs if traveler_ref.strip()}
        if not traveler_set:
            return ()
        return tuple(
            fact
            for fact in self._purchase_limit_facts.values()
            if fact.is_active_for_scoring and fact.traveler_id in traveler_set
        )

    def refunds_since(self, account_id: str, since: datetime) -> tuple[datetime, ...]:
        refunds = [seen_at for seen_at in self._refunds[account_id] if seen_at >= since]
        self._refunds[account_id] = refunds
        return tuple(refunds)


class PatternMatcher:
    def __init__(self, repository: RiskEvaluationRepository) -> None:
        self._repository = repository

    def detect(self, request: "RiskEvaluationRequest", occurred_at: datetime) -> tuple[DetectedPattern, ...]:
        detected: list[DetectedPattern] = []
        if request.route is not None:
            since = occurred_at - timedelta(hours=24)
            same_route = [
                order for order in self._repository.orders_for_account_since(request.account_id, since)
                if order.origin == request.route.origin and order.destination == request.route.destination
            ]
            if len(same_route) + 1 > 3:
                detected.append(DetectedPattern(ScalperPattern.SAME_ROUTE_BULK, 24 * 60 * 60, 3, 25, f"{len(same_route) + 1}/3 same route bookings in 24h"))
        refunds = self._repository.refunds_since(request.account_id, occurred_at - timedelta(days=7))
        if len(refunds) > 3 and any(timedelta(0) <= occurred_at - refund_at <= timedelta(hours=1) for refund_at in refunds):
            detected.append(DetectedPattern(ScalperPattern.RESALE_REFUND_CYCLE, 7 * 24 * 60 * 60, 3, 30, f"{len(refunds)} refunds in 7d with new booking within 1h"))
        searches = _int_from_mapping(request.context, "searchCountLast2m")
        if searches > 20:
            detected.append(DetectedPattern(ScalperPattern.RAPID_SEARCH_THEN_BOOK, 2 * 60, 20, 15, f"{searches}/20 searches before booking"))
        active_limit_facts = self._repository.active_purchase_limit_facts(request.traveler_refs)
        if active_limit_facts:
            detected.append(
                DetectedPattern(
                    ScalperPattern.IDENTITY_PURCHASE_LIMIT_DUPLICATE,
                    24 * 60 * 60,
                    0,
                    30,
                    f"{len(active_limit_facts)} active identity purchase-limit facts for traveler",
                )
            )
        return tuple(detected)


@dataclass(frozen=True, slots=True)
class Route:
    origin: str
    destination: str


@dataclass(frozen=True, slots=True)
class RiskEvaluationRequest:
    order_id: str
    account_id: str
    traveler_refs: tuple[str, ...]
    total_amount_minor: int
    currency: str
    route: Route | None
    departure_date: str
    source_ip: str | None
    channel_id: str
    context: Mapping[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class RiskEvaluationService:
    publisher: EventPublisher
    repository: RiskEvaluationRepository = field(default_factory=RiskEvaluationRepository)
    velocity_counter: VelocityCounterPort = field(default_factory=InMemoryVelocityCounter)
    score_calculator: RiskScoreCalculator = field(default_factory=RiskScoreCalculator)

    def evaluate(self, request: RiskEvaluationRequest, *, correlation_id: str) -> RiskEvaluation:
        now = _occurred_at(request.context)
        rule_results = self._evaluate_rules(request, now)
        patterns = PatternMatcher(self.repository).detect(request, now)
        signals = self._signals(request, now, rule_results, patterns)
        score = self.score_calculator.calculate(signals)
        evaluation = RiskEvaluation.complete(
            evaluation_id=prefixed_id("risk-eval"),
            order_id=request.order_id,
            account_id=request.account_id,
            triggered_rules=rule_results,
            score=score,
            signals=signals,
            evaluated_at=now,
        )
        self.repository.save(evaluation)
        self._record_order_history(request, now)
        self._publish_completed(evaluation, correlation_id)
        if evaluation.score >= 60 or _has_velocity_breach(evaluation):
            self.publisher.publish(_alert_envelope(evaluation, correlation_id, f"risk-evaluation:{evaluation.evaluation_id}"))
        return evaluation

    def get(self, evaluation_id: str) -> RiskEvaluation:
        return self.repository.get(evaluation_id)

    def override(self, evaluation_id: str, *, staff_id: str, reason: str, correlation_id: str) -> RiskEvaluation:
        evaluation = self.repository.get(evaluation_id).override(staff_id=staff_id, reason=reason)
        self.repository.save(evaluation)
        self._publish_completed(evaluation, correlation_id)
        return evaluation

    def handle_event(self, envelope: EventEnvelope) -> None:
        occurred_at = _coerce_datetime(envelope.occurredAt)
        payload = envelope.payload
        if envelope.eventType == "AccountRegistered":
            account_id = _text(payload, "accountId")
            if account_id:
                self.repository.record_account_registered(account_id, occurred_at)
            return
        if envelope.eventType in {"PaymentCaptured", "PaymentFailed"}:
            account_id = _text(payload, "accountId")
            if account_id:
                self.repository.record_payment_attempt(account_id, occurred_at)
            return
        if envelope.eventType == "PostSalesApplied":
            account_id = _text(payload, "accountId")
            if account_id and str(payload.get("type", payload.get("applicationType", ""))).upper().find("REFUND") >= 0:
                self.repository.record_refund(account_id, occurred_at)
            return
        if envelope.eventType == "JourneyOrderCreated":
            try:
                request = request_from_payload(payload)
            except RiskComplianceError:
                return
            self._record_order_history(request, occurred_at)
            self.velocity_counter.increment_and_count(VelocityDimension.ACCOUNT, request.account_id, occurred_at, ACCOUNT_ORDER_RULE.window_seconds)
            for traveler_ref in request.traveler_refs:
                self.velocity_counter.increment_and_count(VelocityDimension.TRAVELER, traveler_ref, occurred_at, TRAVELER_BOOKING_RULE.window_seconds)
            if request.source_ip:
                self.velocity_counter.increment_and_count(VelocityDimension.IP, request.source_ip, occurred_at, IP_ORDER_RULE.window_seconds)

    def _evaluate_rules(self, request: RiskEvaluationRequest, occurred_at: datetime) -> tuple[RuleResult, ...]:
        results = []
        account_count = self.velocity_counter.increment_and_count(VelocityDimension.ACCOUNT, request.account_id, occurred_at, ACCOUNT_ORDER_RULE.window_seconds)
        results.append(ACCOUNT_ORDER_RULE.evaluate(account_count))
        payment_count = self.repository.payment_attempts_since(request.account_id, occurred_at - timedelta(seconds=ACCOUNT_PAYMENT_RULE.window_seconds))
        results.append(ACCOUNT_PAYMENT_RULE.evaluate(payment_count))
        for traveler_ref in request.traveler_refs:
            traveler_count = self.velocity_counter.increment_and_count(VelocityDimension.TRAVELER, traveler_ref, occurred_at, TRAVELER_BOOKING_RULE.window_seconds)
            results.append(TRAVELER_BOOKING_RULE.evaluate(traveler_count))
        if request.source_ip:
            ip_count = self.velocity_counter.increment_and_count(VelocityDimension.IP, request.source_ip, occurred_at, IP_ORDER_RULE.window_seconds)
            results.append(IP_ORDER_RULE.evaluate(ip_count))
        return tuple(results)

    def _signals(self, request: RiskEvaluationRequest, occurred_at: datetime, rules: tuple[RuleResult, ...], patterns: tuple[DetectedPattern, ...]) -> tuple[RiskSignal, ...]:
        signals: list[RiskSignal] = []
        strongest = RiskVerdict.PASS
        closest_ratio = 0.0
        for result in rules:
            strongest = strongest_verdict(strongest, result.result)
            count, threshold = _parse_detail(result.detail)
            if threshold:
                closest_ratio = max(closest_ratio, min(1.0, count / threshold))
        velocity_score = 100 if strongest is RiskVerdict.BLOCK else 70 if strongest is RiskVerdict.CHALLENGE else int(round(closest_ratio * 100))
        signals.append(RiskSignal("velocity_score", strongest.value, velocity_score))
        account_created_at = self.repository.account_created_at(request.account_id)
        if account_created_at is not None:
            age = occurred_at - account_created_at
            if age < timedelta(hours=1):
                signals.append(RiskSignal("account_age_score", age.total_seconds(), 100))
            elif age < timedelta(days=1):
                signals.append(RiskSignal("account_age_score", age.total_seconds(), 50))
        elif request.context.get("newAccount") is True:
            signals.append(RiskSignal("account_age_score", "new", 100))
        if request.context.get("travelerMismatch") is True:
            signals.append(RiskSignal("traveler_mismatch", True, 100))
        if request.currency == "CNY" and request.total_amount_minor > 500_000:
            signals.append(RiskSignal("high_value_order", request.total_amount_minor, 100))
        elif request.context.get("highValueOrder") is True:
            signals.append(RiskSignal("high_value_order", request.total_amount_minor, 100))
        if patterns:
            signals.append(RiskSignal("known_scalper_pattern", [pattern.pattern_type.value for pattern in patterns], 100))
        return tuple(signals)

    def _record_order_history(self, request: RiskEvaluationRequest, occurred_at: datetime) -> None:
        if request.route is None:
            return
        self.repository.record_order(OrderHistoryRecord(request.account_id, request.order_id, request.route.origin, request.route.destination, request.traveler_refs, request.departure_date, occurred_at))

    def _publish_completed(self, evaluation: RiskEvaluation, correlation_id: str) -> None:
        self.publisher.publish(_completed_envelope(evaluation, correlation_id, f"risk-evaluation:{evaluation.evaluation_id}"))


def _has_velocity_breach(evaluation: RiskEvaluation) -> bool:
    return any(rule.result is not RiskVerdict.PASS for rule in evaluation.triggered_rules)


def request_from_payload(payload: Mapping[str, Any]) -> RiskEvaluationRequest:
    order_id = _required_text(payload, "orderId")
    account_id = _required_text(payload, "accountId")
    route = None
    route_value = payload.get("route")
    if isinstance(route_value, Mapping):
        origin = _required_text(route_value, "origin")
        destination = _required_text(route_value, "destination")
        route = Route(origin, destination)
    traveler_refs = tuple(_traveler_ref(value) for value in payload.get("travelerRefs", []) if _traveler_ref(value))
    return RiskEvaluationRequest(
        order_id=order_id,
        account_id=account_id,
        traveler_refs=traveler_refs,
        total_amount_minor=int(payload.get("totalAmountMinor", 0)),
        currency=str(payload.get("currency", "CNY")),
        route=route,
        departure_date=str(payload.get("departureDate", "")),
        source_ip=_text(payload, "sourceIp"),
        channel_id=str(payload.get("channelId", "")),
        context=payload,
    )


def _completed_envelope(evaluation: RiskEvaluation, correlation_id: str, causation_id: str) -> EventEnvelope:
    return envelope_factory(
        event_type="RiskEvaluationCompleted",
        producer=PRODUCER,
        payload=evaluation.to_event_payload(),
        correlation_id=correlation_id,
        causation_id=causation_id,
        occurred_at=rfc3339_utc(evaluation.evaluated_at),
        schema_version=SCHEMA_VERSION,
    )


def _alert_envelope(evaluation: RiskEvaluation, correlation_id: str, causation_id: str) -> EventEnvelope:
    return envelope_factory(
        event_type="RiskAlertRaised",
        producer=PRODUCER,
        payload={
            "evaluationId": evaluation.evaluation_id,
            "orderId": evaluation.order_id,
            "accountId": evaluation.account_id,
            "score": evaluation.score,
            "verdict": evaluation.verdict.value,
            "triggeredRules": [rule.to_dict() for rule in evaluation.triggered_rules if rule.result is not RiskVerdict.PASS],
            "raisedAt": rfc3339_utc(evaluation.evaluated_at),
        },
        correlation_id=correlation_id,
        causation_id=causation_id,
        occurred_at=rfc3339_utc(evaluation.evaluated_at),
        schema_version=SCHEMA_VERSION,
    )


def _occurred_at(context: Mapping[str, Any]) -> datetime:
    for key in ("occurredAt", "createdAt", "requestedAt"):
        value = context.get(key)
        if isinstance(value, str) and value.strip():
            return _coerce_datetime(value)
    return datetime.now(UTC)


def _coerce_datetime(value: datetime | str) -> datetime:
    if isinstance(value, datetime):
        return value.astimezone(UTC) if value.tzinfo else value.replace(tzinfo=UTC)
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


def _required_text(values: Mapping[str, Any], field_name: str) -> str:
    value = _text(values, field_name)
    if value is None:
        raise RiskComplianceError(f"{field_name} is required")
    return value


def _text(values: Mapping[str, Any], field_name: str) -> str | None:
    value = values.get(field_name)
    if isinstance(value, str) and value.strip():
        return value.strip()
    return None


def _traveler_ref(value: Any) -> str | None:
    if isinstance(value, str) and value.strip():
        return value.strip()
    if isinstance(value, Mapping):
        for key in ("travelerRef", "travelerId", "id"):
            found = _text(value, key)
            if found:
                return found
    return None


def _int_from_mapping(values: Mapping[str, Any], field_name: str) -> int:
    value = values.get(field_name)
    return value if isinstance(value, int) else 0


def _parse_detail(detail: str) -> tuple[int, int]:
    try:
        left, rest = detail.split("/", 1)
        right = rest.split(" ", 1)[0]
        return int(left), int(right)
    except (ValueError, IndexError):
        return 0, 0
