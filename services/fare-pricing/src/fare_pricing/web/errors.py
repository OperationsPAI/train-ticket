from __future__ import annotations

from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from fare_pricing.ids import prefixed_uuid7


class ApiError(Exception):
    def __init__(self, code: str, message: str, status_code: int, details: dict[str, Any] | None = None) -> None:
        self.code = code
        self.message = message
        self.status_code = status_code
        self.details = details or {}
        super().__init__(message)


def correlation_id_for(request: Request) -> str:
    return getattr(request.state, "correlation_id", None) or request.headers.get("X-Correlation-Id") or prefixed_uuid7("corr")


def error_body(code: str, message: str, correlation_id: str, details: dict[str, Any] | None = None) -> dict[str, Any]:
    return {
        "code": code,
        "message": message,
        "correlationId": correlation_id,
        "details": details or {},
    }


def error_response(
    request: Request,
    code: str,
    message: str,
    status_code: int,
    details: dict[str, Any] | None = None,
) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content=error_body(code, message, correlation_id_for(request), details),
    )


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def api_error_handler(request: Request, exc: ApiError) -> JSONResponse:
        return error_response(request, exc.code, exc.message, exc.status_code, exc.details)

    @app.exception_handler(HTTPException)
    async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
        detail = exc.detail
        if isinstance(detail, dict) and {"code", "message"}.issubset(detail):
            return error_response(
                request,
                str(detail["code"]),
                str(detail["message"]),
                exc.status_code,
                detail.get("details") if isinstance(detail.get("details"), dict) else {},
            )
        code = "NOT_FOUND" if exc.status_code == 404 else "VALIDATION_FAILED"
        message = str(detail) if detail else "Request failed"
        return error_response(request, code, message, exc.status_code)

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        safe_errors = [
            {
                "loc": list(error.get("loc", ())),
                "type": str(error.get("type", "")),
                "msg": str(error.get("msg", "")),
            }
            for error in exc.errors()
        ]
        return error_response(
            request,
            "VALIDATION_FAILED",
            "Request validation failed",
            400,
            {"errors": safe_errors},
        )
