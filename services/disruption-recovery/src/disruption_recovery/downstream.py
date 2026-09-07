from __future__ import annotations

from dataclasses import dataclass
from json import JSONDecodeError
import os
import time
from typing import Any, Mapping
from urllib import request, error

from train_ticket_platform.outbound import traced_urllib_request

_PROJECTION_LAG_CODES = frozenset({"MISSING_ORDER_PROJECTION", "MISSING_ENTITLEMENT_PROJECTION", "MISSING_WALLET_ACCOUNT"})
_PROJECTION_RETRY_DELAYS_SECONDS = (0.2, 0.4, 0.8, 1.6, 3.2)


class DownstreamError(RuntimeError):
    def __init__(self, message: str, code: str | None = None) -> None:
        super().__init__(message)
        self.code = code


def _env_name(service: str) -> str:
    return service.upper().replace("-", "_") + "_URL"


@dataclass(frozen=True)
class ServiceUrls:
    post_sales: str
    wallet_promotion: str
    transfer_management: str

    @classmethod
    def from_env(cls) -> "ServiceUrls":
        def base(service: str) -> str:
            return os.getenv(_env_name(service), f"http://{service}:8080").rstrip("/")
        return cls(post_sales=base("post-sales"), wallet_promotion=base("wallet-promotion"), transfer_management=base("transfer-management"))


class DownstreamHttpClient:
    def __init__(self, urls: ServiceUrls | None = None, timeout: float | None = None) -> None:
        self.urls = urls or ServiceUrls.from_env()
        self.timeout = timeout or float(os.getenv("DOWNSTREAM_TIMEOUT_SECONDS", "10"))

    def open_refund_case(self, body: Mapping[str, Any], idempotency_key: str, correlation_id: str) -> Mapping[str, Any]:
        return self._post_awaiting_projections("post_sales", "/api/v1/post-sales-cases", body, idempotency_key, correlation_id)

    def issue_compensation(self, body: Mapping[str, Any], idempotency_key: str, correlation_id: str) -> Mapping[str, Any]:
        return self._post_awaiting_projections("wallet_promotion", "/api/v1/benefits", body, idempotency_key, correlation_id)

    def reaccommodate_connection(self, connection_id: str, body: Mapping[str, Any], idempotency_key: str, correlation_id: str) -> Mapping[str, Any]:
        from urllib.parse import quote
        return self._request("transfer_management", f"/api/v1/connections/{quote(connection_id, safe='')}/reaccommodate", body, idempotency_key, correlation_id)

    def _post_awaiting_projections(self, service: str, path: str, body: Mapping[str, Any], idempotency_key: str, correlation_id: str) -> Mapping[str, Any]:
        for delay in _PROJECTION_RETRY_DELAYS_SECONDS:
            try:
                return self._request(service, path, body, idempotency_key, correlation_id)
            except DownstreamError as exc:
                if exc.code not in _PROJECTION_LAG_CODES:
                    raise
                time.sleep(delay)
        return self._request(service, path, body, idempotency_key, correlation_id)

    def _request(self, service: str, path: str, body: Mapping[str, Any], idempotency_key: str, correlation_id: str) -> Mapping[str, Any]:
        import json
        base = getattr(self.urls, service)
        # traced_urllib_request, not request.Request: the platform kit attaches
        # the active span's W3C traceparent so the callee's server span joins
        # this trace. The correlation and idempotency headers are unchanged.
        req = traced_urllib_request(
            base + path,
            data=json.dumps(body, separators=(",", ":")).encode("utf-8"),
            method="POST",
            headers={"Accept": "application/json", "Content-Type": "application/json", "Idempotency-Key": idempotency_key, "X-Correlation-Id": correlation_id},
        )
        try:
            with request.urlopen(req, timeout=self.timeout) as response:  # noqa: S310 configured in-cluster URLs
                parsed = json.loads(response.read().decode("utf-8") or "{}")
                if not isinstance(parsed, dict):
                    raise DownstreamError(f"{service} returned a non-object response")
                return parsed
        except error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
            message, code = _downstream_failure(service, raw, exc.reason)
            raise DownstreamError(message, code) from exc
        except error.URLError as exc:
            raise DownstreamError(f"{service} unavailable: {exc.reason}") from exc
        except JSONDecodeError as exc:
            raise DownstreamError(f"{service} returned invalid JSON") from exc


def _downstream_failure(service: str, raw: str, fallback: str) -> tuple[str, str | None]:
    import json
    try:
        parsed = json.loads(raw)
    except JSONDecodeError:
        parsed = None
    message = f"{service} request failed: {fallback}"
    code = None
    if isinstance(parsed, dict):
        parsed_message = parsed.get("message") or parsed.get("msg") or parsed.get("error")
        if isinstance(parsed_message, str) and parsed_message.strip():
            message = parsed_message.strip()
        details = parsed.get("details")
        domain_code = details.get("domainCode") if isinstance(details, dict) else None
        parsed_code = domain_code or parsed.get("code")
        if isinstance(parsed_code, str) and parsed_code.strip():
            code = parsed_code.strip()
    return message, code
