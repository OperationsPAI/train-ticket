from __future__ import annotations

import logging

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from train_ticket_platform.http import error_response, register_exception_handlers as register_base

from disruption_recovery.application.service import NotFoundError, PreconditionFailedError
from disruption_recovery.domain import DomainError, PreconditionFailed
from disruption_recovery.downstream import DownstreamError


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
        # The downstream rejection reason MUST be observable: a bare 503
        # cost an hour of blind diagnosis at the wave-17 gate.
        logging.getLogger("disruption_recovery.downstream").warning(
            "downstream failure path=%s code=%s message=%s",
            request.url.path,
            exc.code,
            exc,
        )
        # A downstream that answered with a client error refused the request on
        # its own terms -- "a refund or change case is already in progress for
        # this order", for one -- and that is not an availability failure. The
        # caller gets the downstream's status, narrowed to the contract's own
        # vocabulary, with the downstream's code beside it. Anything else, a
        # connection failure or a server error, stays 503.
        if exc.status is not None and 400 <= exc.status < 500:
            contract_code = {
                409: "CONFLICT",
                412: "PRECONDITION_FAILED",
                422: "DOMAIN_RULE_VIOLATION",
            }.get(exc.status, "VALIDATION_FAILED")
            return error_response(
                request,
                contract_code,
                str(exc),
                exc.status,
                {"domainCode": exc.code} if exc.code else {},
            )
        return error_response(request, "UNAVAILABLE", "Downstream service unavailable", 503, {"domainCode": exc.code} if exc.code else {})
