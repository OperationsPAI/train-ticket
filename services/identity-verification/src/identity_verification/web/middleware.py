from collections.abc import Callable, Mapping
from contextlib import AbstractContextManager, nullcontext
from typing import Protocol

from starlette.datastructures import Headers, MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send
from train_ticket_platform.ids import new_uuid7

from identity_verification.runtime import profile


TraceHook = Callable[[str, Mapping[str, object]], None]


class RuntimeSpan(AbstractContextManager["RuntimeSpan"], Protocol):
    def set_attribute(self, key: str, value: object) -> None: ...
    def __exit__(self, exc_type: object, exc: object, traceback: object) -> bool | None: ...


class RuntimeTracer(Protocol):
    def start_as_current_span(self, name: str) -> RuntimeSpan: ...


class RequestContextMiddleware:
    def __init__(self, app: ASGIApp, tracer: TraceHook | None = None, otel_tracer: RuntimeTracer | None = None) -> None:
        self.app = app
        self.tracer = tracer
        self.otel_tracer = otel_tracer

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        headers = Headers(scope=scope)
        request_id = headers.get("X-Request-ID") or new_uuid7()
        correlation_id = headers.get("X-Correlation-Id") or request_id
        state = scope.setdefault("state", {})
        state["request_id"] = request_id
        state["correlation_id"] = correlation_id
        attrs = {"service.name": profile()["service_id"], "request_id": request_id,
                 "correlation_id": correlation_id, "http.request.method": scope["method"], "url.path": scope["path"]}
        if self.tracer is not None:
            self.tracer("http.request.start", dict(attrs))
        context = nullcontext(None) if self.otel_tracer is None else self.otel_tracer.start_as_current_span(f"{scope['method']} {scope['path']}")
        with context as span:
            if span is not None:
                for key, value in attrs.items():
                    span.set_attribute(key, value)
            status_code = None

            async def send_response(message: Message) -> None:
                nonlocal status_code
                if message["type"] == "http.response.start":
                    response_headers = MutableHeaders(scope=message)
                    response_headers["X-Request-ID"] = request_id
                    response_headers["X-Correlation-Id"] = correlation_id
                    status_code = message["status"]
                    if span is not None:
                        span.set_attribute("http.response.status_code", status_code)
                await send(message)

            await self.app(scope, receive, send_response)
            if self.tracer is not None and status_code is not None:
                self.tracer("http.request.complete", {**attrs, "status_code": status_code})


class IdentityUnitOfWorkMiddleware:
    def __init__(self, app: ASGIApp, unit_of_work: Callable[[], AbstractContextManager]) -> None:
        self.app = app
        self.unit_of_work = unit_of_work

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        with self.unit_of_work():
            await self.app(scope, receive, send)
