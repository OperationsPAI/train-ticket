import concurrent.futures
import json
import os
from pathlib import Path
from urllib.parse import quote, urlencode
from uuid import uuid4

import psycopg
import pytest

from risk_compliance.application import RiskComplianceService
from risk_compliance.adapters.storage.postgres import PostgresAssessmentRepository, TransactionalOutboxPublisher, _assessment_from_json
from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.storage import DatabasePool, run_migrations

pytestmark = pytest.mark.skipif(not os.environ.get("TEST_RISK_BOOKING_SAMPLES"), reason="requires real risk and booking events and TEST_DATABASE_URL")


@pytest.fixture
def database():
    url = os.environ["TEST_DATABASE_URL"]
    schema = "booking_risk_" + uuid4().hex
    with psycopg.connect(url, autocommit=True) as admin:
        admin.execute(f"CREATE SCHEMA {schema}")
    pool = DatabasePool(url + "?" + urlencode({"options": "-c search_path=" + schema}, quote_via=quote))
    run_migrations(pool, Path(__file__).parents[1] / "migrations")
    try:
        yield pool
    finally:
        pool.close()
        with psycopg.connect(url, autocommit=True) as admin:
            admin.execute(f"DROP SCHEMA {schema} CASCADE")


def service_for(pool):
    repository = PostgresAssessmentRepository(pool)
    return RiskComplianceService(TransactionalOutboxPublisher(repository), repository)


def samples():
    return json.loads(Path(os.environ["TEST_RISK_BOOKING_SAMPLES"]).read_text())


def completion(pool):
    with pool.connection() as conn:
        rows = conn.execute("SELECT envelope FROM outbox WHERE envelope->>'eventType'='RiskAssessmentCompleted'").fetchall()
    return [row[0] for row in rows]


@pytest.mark.parametrize("decision", ["ALLOW", "DENY"])
def test_booking_uses_persisted_assessment_and_replay_is_idempotent(database, decision):
    sample = samples()[decision]
    request = EventEnvelope.from_json_dict(sample["request"])
    result = _assessment_from_json(sample["assessment"])
    service = service_for(database)
    with service.repository.transaction():
        service.repository.save(result)
        service.repository.remember_order_account(result.subjectRef, request.payload["accountId"])
        service.repository.save_order_assessment(result, request.payload["accountId"])
    service.handle_event(request)
    restarted = service_for(database)
    restarted.handle_event(request)
    with database.connection() as conn:
        conn.execute("DELETE FROM processed_events WHERE event_id=%s", (request.eventId,))
    restarted.handle_event(request)
    [event] = completion(database)
    assert event["payload"] == {"sagaId": request.payload["sagaId"], "journeyOrderId": result.subjectRef,
        "accountId": request.payload["accountId"], "assessmentId": result.assessmentId,
        "verdict": "PASS" if decision == "ALLOW" else "BLOCK", "riskScore": result.score}
    assert event["causationId"] == request.eventId
    assert event["correlationId"] == request.correlationId
    if os.environ.get("TEST_RISK_COMPLETION_DIRECTORY"):
        Path(os.environ["TEST_RISK_COMPLETION_DIRECTORY"], f"risk-completion-{decision}.json").write_text(json.dumps(event))


@pytest.mark.parametrize("ordering", ["request_first", "order_first", "concurrent", "rollback"])
def test_early_booking_request_is_durable_and_completed_with_the_order_assessment(database, ordering):
    sample = samples()["ALLOW"]
    request = EventEnvelope.from_json_dict(sample["request"])
    order = EventEnvelope.from_json_dict(sample["created"])
    service = service_for(database)
    if ordering in ("request_first", "rollback"):
        service.handle_event(request)
        assert completion(database) == []
        with database.connection() as conn:
            assert conn.execute("SELECT envelope FROM booking_risk_requests WHERE completed_at IS NULL").fetchone()[0] == request.to_json_dict()
        service = service_for(database)
        if ordering == "rollback":
            with database.connection() as conn:
                conn.execute("ALTER TABLE outbox ADD CONSTRAINT reject_booking_completion CHECK (envelope->>'eventType' <> 'RiskAssessmentCompleted')")
            with pytest.raises(psycopg.errors.CheckViolation):
                service.handle_event(order)
            with database.connection() as conn:
                assert conn.execute("SELECT count(*) FROM risk_assessment_snapshots").fetchone()[0] == 0
                assert conn.execute("SELECT count(*) FROM processed_events WHERE event_id=%s", (order.eventId,)).fetchone()[0] == 0
                assert conn.execute("SELECT count(*) FROM booking_risk_requests WHERE completed_at IS NULL").fetchone()[0] == 1
                conn.execute("ALTER TABLE outbox DROP CONSTRAINT reject_booking_completion")
        service.handle_event(order)
    elif ordering == "order_first":
        service.handle_event(order)
        service.handle_event(request)
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            futures = [executor.submit(service.handle_event, event) for event in (request, order)]
            for future in futures:
                future.result(timeout=15)
    result = service.repository.order_assessment(order.payload["orderId"])
    assert result is not None
    [event] = completion(database)
    assert event["payload"]["assessmentId"] == result.assessmentId
    assert event["payload"]["riskScore"] == result.score
    assert event["payload"]["verdict"] == {"ALLOW": "PASS", "DENY": "BLOCK", "CHALLENGE": "CHALLENGE", "HOLD": "HOLD"}[result.decision]
    service.handle_event(request)
    service.handle_event(order)
    assert completion(database) == [event]
    with database.connection() as conn:
        assert conn.execute("SELECT count(*) FROM booking_risk_requests WHERE completed_at IS NULL").fetchone()[0] == 0


def test_existing_order_assessment_is_loaded_by_order(database):
    sample = samples()["DENY"]
    request = EventEnvelope.from_json_dict(sample["request"])
    result = _assessment_from_json(sample["assessment"])
    service = service_for(database)
    service.repository.save(result)
    service.repository.remember_order_account(result.subjectRef, request.payload["accountId"])
    assert service.repository.order_assessment(result.subjectRef) == result
    service.handle_event(request)
    assert completion(database)[0]["payload"]["verdict"] == "BLOCK"
