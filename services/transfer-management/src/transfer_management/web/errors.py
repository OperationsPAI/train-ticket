from __future__ import annotations

import logging

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from train_ticket_platform.http import error_response, register_exception_handlers as register_base

from transfer_management.application.service import NotFoundError, PreconditionFailedError
from transfer_management.domain import DomainError, PreconditionFailed
from transfer_management.downstream import DownstreamError


def register_exception_handlers(app: FastAPI) -> None:
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
        logging.getLogger("transfer_management.downstream").warning("downstream failure path=%s code=%s message=%s", request.url.path, exc.code, exc)
        return error_response(request, "UNAVAILABLE", "Downstream service unavailable", 503, {"domainCode": exc.code} if exc.code else {})
