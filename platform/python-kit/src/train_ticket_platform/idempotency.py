from __future__ import annotations

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


async def response_body(response: Response) -> bytes:
    body = b""
    async for chunk in response.body_iterator:  # type: ignore[attr-defined]
        body += chunk
    return body


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
        store: IdempotencyStore,
        *,
        require_key: bool = True,
        include_path_prefixes: tuple[str, ...] = ("/api/",),
        exclude_path_prefixes: tuple[str, ...] = (),
        error_body_factory: ErrorBodyFactory = default_error_body,
    ) -> None:
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

    async def __call__(self, request: Request, call_next: Any) -> Response:
        if not self.applies_to(request):
            return await call_next(request)

        key = request.headers.get(IDEMPOTENCY_KEY_HEADER)
        if not key:
            if not self._require_key:
                return await call_next(request)
            self._ensure_request_state(request)
            return validation_failed_response(request, "Idempotency-Key header is required", self._error_body_factory)
        try:
            canonical_key = require_uuid7_idempotency_key(key)
        except IdempotencyKeyError as exc:
            self._ensure_request_state(request)
            return validation_failed_response(request, str(exc), self._error_body_factory)

        body = await request.body()
        fingerprint = http_request_fingerprint(request.method, request.url.path, request.url.query, body)
        scope = idempotency_scope(request)
        request.state.idempotency_decision = IdempotencyDecision(scope=scope, key=canonical_key, fingerprint=fingerprint)
        existing = self._store.get(scope, canonical_key)
        if existing is None:
            existing = self._store.get(f"{request.method.upper()} {request.url.path}", canonical_key)
        if existing is not None:
            if existing.fingerprint != fingerprint:
                return key_reused_response(request, self._error_body_factory)
            if existing.pending_events:
                response = await call_next(request)
                raw_body = await response_body(response)
                return Response(
                    content=raw_body,
                    status_code=response.status_code,
                    headers=dict(response.headers),
                    media_type=response.media_type,
                    background=response.background,
                )
            self._ensure_request_state(request)
            return replay_response(existing, request)

        response = await call_next(request)
        raw_body = await response_body(response)
        if 200 <= response.status_code < 400:
            headers = {name: value for name, value in response.headers.items() if name.lower() in {"content-type"}}
            self._store.put(
                scope,
                canonical_key,
                IdempotencyRecord(
                    fingerprint=fingerprint,
                    status_code=response.status_code,
                    response_body=decode_response_body(raw_body),
                    headers=headers,
                ),
            )
        return Response(
            content=raw_body,
            status_code=response.status_code,
            headers=dict(response.headers),
            media_type=response.media_type,
            background=response.background,
        )

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
    middleware = IdempotencyMiddleware(
        configured_store,
        require_key=require_key,
        include_path_prefixes=include_path_prefixes,
        exclude_path_prefixes=exclude_path_prefixes,
        error_body_factory=error_body_factory,
    )

    prior_middleware_count = len(getattr(app, "user_middleware", ()))

    @app.middleware("http")
    async def idempotency_middleware(request: Request, call_next: Any) -> Response:
        return await middleware(request, call_next)

    if prior_middleware_count:
        app.user_middleware.append(app.user_middleware.pop(0))
        app.middleware_stack = None

    return configured_store
