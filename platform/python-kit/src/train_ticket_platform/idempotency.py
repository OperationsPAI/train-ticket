from __future__ import annotations

from starlette.concurrency import run_in_threadpool
from starlette.datastructures import Headers
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from collections import OrderedDict
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from hashlib import sha256
import json
from typing import Any, Protocol

from fastapi import Request
from fastapi.responses import JSONResponse, Response

from .http import canonical_error_body, correlation_id_for
from .ids import is_uuid7, new_uuid7

IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
IDEMPOTENCY_REUSED_CODE = "IDEMPOTENCY_KEY_REUSED"
VALIDATION_FAILED_CODE = "VALIDATION_FAILED"


class IdempotencyKeyError(ValueError):
    pass


class IdempotencyKeyReusedError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class IdempotencyDecision:
    scope: str
    key: str
    fingerprint: str


@dataclass(frozen=True, slots=True)
class IdempotencyRecord:
    fingerprint: str
    status_code: int
    response_body: dict[str, Any] | list[Any] | str | int | float | bool | None
    headers: dict[str, str] | None = None
    pending_events: tuple[Mapping[str, Any], ...] = ()

    @property
    def request_hash(self) -> str:
        return self.fingerprint

    @property
    def body(self) -> dict[str, Any] | list[Any] | str | int | float | bool | None:
        return self.response_body


class IdempotencyStore(Protocol):
    def get(self, scope: str, key: str) -> IdempotencyRecord | None: ...

    def put(self, scope: str, key: str, record: IdempotencyRecord) -> None: ...


class BoundedInMemoryIdempotencyStore(IdempotencyStore):
    def __init__(self, max_entries: int = 1024) -> None:
        self._max_entries = max_entries
        self._records: OrderedDict[tuple[str, str], IdempotencyRecord] = OrderedDict()

    def get(self, scope: str, key: str) -> IdempotencyRecord | None:
        compound_key = (scope, key)
        record = self._records.get(compound_key)
        if record is not None:
            self._records.move_to_end(compound_key)
        return record

    def put(self, scope: str, key: str, record: IdempotencyRecord) -> None:
        compound_key = (scope, key)
        self._records[compound_key] = record
        self._records.move_to_end(compound_key)
        while len(self._records) > self._max_entries:
            self._records.popitem(last=False)


def require_uuid7_idempotency_key(key: str | None) -> str:
    if not key:
        raise IdempotencyKeyError("Idempotency-Key header is required")
    if not is_uuid7(key):
        raise IdempotencyKeyError("Idempotency-Key must be a UUID v7")
    return key


def request_fingerprint(body: Any) -> str:
    canonical = json.dumps(body, sort_keys=True, separators=(",", ":"), default=str)
    return sha256(canonical.encode("utf-8")).hexdigest()


def http_request_fingerprint(method: str, path: str, query: str, body: bytes) -> str:
    material = b"\n".join(
        [
            method.upper().encode("utf-8"),
            path.encode("utf-8"),
            query.encode("utf-8"),
            body,
        ]
    )
    return sha256(material).hexdigest()


def idempotency_scope(request: Request) -> str:
    return request.url.path


ErrorBodyFactory = Callable[[Request, str, str], Mapping[str, Any]]


def default_error_body(request: Request, code: str, message: str) -> Mapping[str, Any]:
    return canonical_error_body(code, message, correlation_id_for(request))


def validation_failed_response(request: Request, message: str, error_body_factory: ErrorBodyFactory = default_error_body) -> JSONResponse:
    return JSONResponse(
        status_code=400,
        content=dict(error_body_factory(request, VALIDATION_FAILED_CODE, message)),
    )


def key_reused_response(request: Request, error_body_factory: ErrorBodyFactory = default_error_body) -> JSONResponse:
    return JSONResponse(
        status_code=422,
        content=dict(error_body_factory(request, IDEMPOTENCY_REUSED_CODE, "Idempotency-Key reused with a different request body")),
    )


def decode_response_body(body: bytes) -> Any:
    if not body:
        return None
    text = body.decode("utf-8")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text


def replay_response(record: IdempotencyRecord, request: Request | None = None) -> JSONResponse:
    headers = dict(record.headers or {})
    if request is not None:
        request_id = getattr(request.state, "request_id", None)
        correlation_id = getattr(request.state, "correlation_id", None)
        if request_id is not None:
            headers["X-Request-Id"] = str(request_id)
        if correlation_id is not None:
            headers["X-Correlation-Id"] = str(correlation_id)
    return JSONResponse(
        status_code=record.status_code,
        content=record.response_body,
        headers=headers,
    )


class IdempotencyMiddleware:
    """Canonical FastAPI idempotency middleware for state-changing POSTs."""

    def __init__(
        self,
        app: ASGIApp,
        store: IdempotencyStore,
        *,
        require_key: bool = True,
        include_path_prefixes: tuple[str, ...] = ("/api/",),
        exclude_path_prefixes: tuple[str, ...] = (),
        error_body_factory: ErrorBodyFactory = default_error_body,
    ) -> None:
        self.app = app
        self._store = store
        self._require_key = require_key
        self._include_path_prefixes = include_path_prefixes
        self._exclude_path_prefixes = exclude_path_prefixes
        self._error_body_factory = error_body_factory

    def applies_to(self, request: Request) -> bool:
        if request.method.upper() != "POST":
            return False
        path = request.url.path
        if self._exclude_path_prefixes and any(path.startswith(prefix) for prefix in self._exclude_path_prefixes):
            return False
        return not self._include_path_prefixes or any(path.startswith(prefix) for prefix in self._include_path_prefixes)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        request = Request(scope, receive)
        if not self.applies_to(request):
            await self.app(scope, receive, send)
            return

        key = request.headers.get(IDEMPOTENCY_KEY_HEADER)
        if not key:
            if not self._require_key:
                await self.app(scope, receive, send)
                return
            self._ensure_request_state(request)
            await validation_failed_response(request, "Idempotency-Key header is required", self._error_body_factory)(scope, receive, send)
            return
        try:
            canonical_key = require_uuid7_idempotency_key(key)
        except IdempotencyKeyError as exc:
            self._ensure_request_state(request)
            await validation_failed_response(request, str(exc), self._error_body_factory)(scope, receive, send)
            return

        body = await request.body()
        fingerprint = http_request_fingerprint(request.method, request.url.path, request.url.query, body)
        key_scope = idempotency_scope(request)
        request.state.idempotency_decision = IdempotencyDecision(scope=key_scope, key=canonical_key, fingerprint=fingerprint)
        existing = await run_in_threadpool(self._store.get, key_scope, canonical_key)
        if existing is not None:
            if existing.fingerprint != fingerprint:
                await key_reused_response(request, self._error_body_factory)(scope, receive, send)
                return
            if not existing.pending_events:
                self._ensure_request_state(request)
                await replay_response(existing, request)(scope, receive, send)
                return

        body_delivered = False

        async def receive_body() -> Message:
            nonlocal body_delivered
            if not body_delivered:
                body_delivered = True
                return {"type": "http.request", "body": body, "more_body": False}
            return await receive()

        if existing is not None:
            await self.app(scope, receive_body, send)
            return

        response_start: Message | None = None
        chunks: list[bytes] = []

        async def send_response(message: Message) -> None:
            nonlocal response_start
            if message["type"] == "http.response.start" and 200 <= message["status"] < 400:
                response_start = message
                return
            if response_start is None or message["type"] != "http.response.body":
                await send(message)
                return
            chunks.append(message.get("body", b""))
            if message.get("more_body", False):
                return
            raw_body = b"".join(chunks)
            headers = {name: value for name, value in Headers(scope=response_start).items() if name == "content-type"}
            await run_in_threadpool(self._store.put, key_scope, canonical_key,
                IdempotencyRecord(fingerprint=fingerprint, status_code=response_start["status"],
                                  response_body=decode_response_body(raw_body), headers=headers))
            await send(response_start)
            await send({**message, "body": raw_body})
            response_start = None
            chunks.clear()

        await self.app(scope, receive_body, send_response)

    @staticmethod
    def _ensure_request_state(request: Request) -> None:
        if getattr(request.state, "correlation_id", None) is None:
            supplied = None
            for name, value in request.headers.items():
                if name.lower() == "x-correlation-id":
                    supplied = value
                    break
            request.state.correlation_id = supplied if supplied and is_uuid7(supplied) else new_uuid7()
        if getattr(request.state, "request_id", None) is None:
            request.state.request_id = new_uuid7()


def configure_idempotency_middleware(
    app: Any,
    store: IdempotencyStore | None = None,
    *,
    require_key: bool = True,
    include_path_prefixes: tuple[str, ...] = ("/api/",),
    exclude_path_prefixes: tuple[str, ...] = (),
    error_body_factory: ErrorBodyFactory = default_error_body,
) -> IdempotencyStore:
    configured_store = store or BoundedInMemoryIdempotencyStore()
    app.state.idempotency_store = configured_store
    prior_middleware_count = len(getattr(app, "user_middleware", ()))
    app.add_middleware(
        IdempotencyMiddleware,
        store=configured_store,
        require_key=require_key,
        include_path_prefixes=include_path_prefixes,
        exclude_path_prefixes=exclude_path_prefixes,
        error_body_factory=error_body_factory,
    )

    if prior_middleware_count:
        app.user_middleware.append(app.user_middleware.pop(0))
        app.middleware_stack = None

    return configured_store
