from __future__ import annotations

from typing import Any, Mapping

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from .ids import new_prefixed_uuid7


class ApiError(Exception):
    def __init__(self, code: str, message: str, status_code: int, details: Mapping[str, Any] | None = None) -> None:
        self.code = code
        self.message = message
        self.status_code = status_code
        self.details = dict(details or {})
        super().__init__(message)


def safe_validation_errors(exc: RequestValidationError) -> list[dict[str, Any]]:
    return [
        {
            "loc": list(error.get("loc", ())),
            "type": str(error.get("type", "")),
            "msg": str(error.get("msg", "")),
        }
        for error in exc.errors()
    ]


def correlation_id_for(request: Request) -> str:
    return str(getattr(request.state, "correlation_id", None) or request.headers.get("X-Correlation-Id") or new_prefixed_uuid7("corr"))


def canonical_error_body(code: str, message: str, correlation_id: str, details: Mapping[str, Any] | None = None) -> dict[str, Any]:
    return {
        "code": code,
        "message": message,
        "correlationId": correlation_id,
        "details": dict(details or {}),
    }


def error_response(
    request: Request,
    code: str,
    message: str,
    status_code: int,
    details: Mapping[str, Any] | None = None,
) -> JSONResponse:
    return JSONResponse(status_code=status_code, content=canonical_error_body(code, message, correlation_id_for(request), details))


def http_status_to_code(status_code: int) -> str:
    if status_code == 404:
        return "NOT_FOUND"
    if status_code == 409:
        return "CONFLICT"
    if status_code == 412:
        return "PRECONDITION_FAILED"
    if status_code == 422:
        return "DOMAIN_RULE_VIOLATION"
    if status_code == 503:
        return "UNAVAILABLE"
    return "VALIDATION_FAILED" if status_code < 500 else "UNAVAILABLE"


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def api_error_handler(request: Request, exc: ApiError) -> JSONResponse:
        return error_response(request, exc.code, exc.message, exc.status_code, exc.details)

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        return error_response(request, "VALIDATION_FAILED", "Request validation failed", 400, {"errors": safe_validation_errors(exc)})

    @app.exception_handler(HTTPException)
    async def fastapi_http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
        detail = exc.detail
        if isinstance(detail, dict) and {"code", "message"}.issubset(detail):
            details = detail.get("details") if isinstance(detail.get("details"), Mapping) else {}
            return error_response(request, str(detail["code"]), str(detail["message"]), exc.status_code, details)
        return error_response(request, http_status_to_code(exc.status_code), str(detail) if detail else "Request failed", exc.status_code)

    @app.exception_handler(StarletteHTTPException)
    async def starlette_http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        return error_response(request, http_status_to_code(exc.status_code), str(exc.detail), exc.status_code)
