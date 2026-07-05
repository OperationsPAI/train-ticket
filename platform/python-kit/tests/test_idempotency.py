from fastapi import FastAPI
from fastapi.testclient import TestClient

from train_ticket_platform.idempotency import configure_idempotency_middleware


VALID_KEY = "0194f2e0-7b3e-7610-8284-5c26e8b0c001"
V4_KEY = "550e8400-e29b-41d4-a716-446655440000"


def create_app() -> FastAPI:
    app = FastAPI()
    configure_idempotency_middleware(app, include_path_prefixes=("/commands",))
    calls = {"count": 0}

    @app.post("/commands")
    async def command(payload: dict[str, object]) -> dict[str, object]:
        calls["count"] += 1
        return {"result": "created", "calls": calls["count"], "payload": payload}

    return app


def test_replay_and_reuse_policy() -> None:
    client = TestClient(create_app())

    first = client.post("/commands", json={"a": 1}, headers={"Idempotency-Key": VALID_KEY})
    replay = client.post("/commands", json={"a": 1}, headers={"Idempotency-Key": VALID_KEY})
    reused = client.post("/commands", json={"a": 2}, headers={"Idempotency-Key": VALID_KEY})

    assert first.status_code == 200
    assert replay.status_code == 200
    assert replay.json() == first.json()
    assert reused.status_code == 422
    assert reused.json()["code"] == "IDEMPOTENCY_KEY_REUSED"


def test_key_is_required_and_must_be_uuid7() -> None:
    client = TestClient(create_app())

    missing = client.post("/commands", json={"a": 1})
    malformed = client.post("/commands", json={"a": 1}, headers={"Idempotency-Key": "not-a-uuid"})
    v4 = client.post("/commands", json={"a": 1}, headers={"Idempotency-Key": V4_KEY})

    assert missing.status_code == 400
    assert malformed.status_code == 400
    assert v4.status_code == 400
    assert missing.json()["code"] == "VALIDATION_FAILED"
    assert malformed.json()["code"] == "VALIDATION_FAILED"
    assert v4.json()["code"] == "VALIDATION_FAILED"
