from __future__ import annotations

import logging
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from train_ticket_platform.http import error_response, register_exception_handlers as register_base
from identity_verification.application.service import NotFoundError, PreconditionFailedError
from identity_verification.domain import DomainError, PreconditionFailed


class DownstreamError(RuntimeError):
    def __init__(self, message: str, code: str | None = None) -> None:
        super().__init__(message); self.code = code


def register_exception_handlers(app: FastAPI) -> None:
    from train_ticket_platform.storage import OptimisticConcurrencyError

    @app.exception_handler(OptimisticConcurrencyError)
    async def occ(request: Request, exc: OptimisticConcurrencyError) -> JSONResponse:
        # Concurrent writers race on the same aggregate; callers retry.
        return error_response(request, "CONFLICT", "Concurrent update, retry", 409)

    register_base(app)

    @app.exception_handler(NotFoundError)
    async def not_found(request: Request, exc: NotFoundError) -> JSONResponse:
        return error_response(request, "NOT_FOUND", str(exc), 404)

    @app.exception_handler(PreconditionFailed)
    @app.exception_handler(PreconditionFailedError)
    async def precondition(request: Request, exc: PreconditionFailed) -> JSONResponse:
        return error_response(request, "PRECONDITION_FAILED", str(exc), 412)

    @app.exception_handler(DomainError)
    async def domain(request: Request, exc: DomainError) -> JSONResponse:
        return error_response(request, "DOMAIN_RULE_VIOLATION", str(exc), 422)

    @app.exception_handler(DownstreamError)
    async def downstream(request: Request, exc: DownstreamError) -> JSONResponse:
        logging.getLogger("identity_verification.downstream").warning("downstream failure path=%s code=%s message=%s", request.url.path, exc.code, exc)
        return error_response(request, "UNAVAILABLE", "Downstream service unavailable", 503, {"domainCode": exc.code} if exc.code else {})
