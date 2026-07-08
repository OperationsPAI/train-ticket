from __future__ import annotations

from dataclasses import dataclass
from json import JSONDecodeError
import os
from typing import Any, Mapping
from urllib import parse, request, error


class DownstreamError(RuntimeError):
    def __init__(self, message: str, code: str | None = None) -> None:
        super().__init__(message)
        self.code = code


def _env_name(service: str) -> str:
    return service.upper().replace("-", "_") + "_URL"


@dataclass(frozen=True)
class ServiceUrls:
    trip_planning: str
    fare_pricing: str
    offer_management: str
    journey_order: str
    booking_orchestration: str
    payment: str
    entitlement_ticketing: str
    fulfillment: str
    post_sales: str
    traveler_profile: str

    @classmethod
    def from_env(cls) -> "ServiceUrls":
        def base(service: str) -> str:
            return os.getenv(_env_name(service), f"http://{service}:8080").rstrip("/")

        return cls(
            trip_planning=base("trip-planning"),
            fare_pricing=base("fare-pricing"),
            offer_management=base("offer-management"),
            journey_order=base("journey-order"),
            booking_orchestration=base("booking-orchestration"),
            payment=base("payment"),
            entitlement_ticketing=base("entitlement-ticketing"),
            fulfillment=base("fulfillment"),
            post_sales=base("post-sales"),
            traveler_profile=base("traveler-profile"),
        )


class DownstreamClient:
    def __init__(self, urls: ServiceUrls | None = None, timeout: float | None = None) -> None:
        self.urls = urls or ServiceUrls.from_env()
        self.timeout = timeout or float(os.getenv("DOWNSTREAM_TIMEOUT_SECONDS", "10"))

    def get(self, service: str, path: str, headers: Mapping[str, str] | None = None) -> dict[str, Any]:
        return self._request(service, "GET", path, None, headers)

    def post(self, service: str, path: str, body: Mapping[str, Any], headers: Mapping[str, str] | None = None) -> dict[str, Any]:
        return self._request(service, "POST", path, body, headers)

    def _request(
        self,
        service: str,
        method: str,
        path: str,
        body: Mapping[str, Any] | None,
        headers: Mapping[str, str] | None,
    ) -> dict[str, Any]:
        import json

        base = getattr(self.urls, service.replace("-", "_"))
        data = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
        req = request.Request(
            base + path,
            data=data,
            method=method,
            headers={"Accept": "application/json", "Content-Type": "application/json", **dict(headers or {})},
        )
        try:
            with request.urlopen(req, timeout=self.timeout) as response:  # noqa: S310 - URLs are configured service bases
                raw = response.read().decode("utf-8")
                if not raw:
                    return {}
                parsed_body = json.loads(raw)
                if not isinstance(parsed_body, dict):
                    raise DownstreamError(f"{service} returned a non-object response")
                return parsed_body
        except error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
            message, code = _downstream_failure(service, raw, exc.reason)
            raise DownstreamError(message, code=code) from exc
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
        # Services wrap the specific domain code in details.domainCode under a
        # generic top-level code (e.g. DOMAIN_RULE_VIOLATION); prefer the
        # specific one.
        details = parsed.get("details")
        domain_code = details.get("domainCode") if isinstance(details, dict) else None
        parsed_code = domain_code or parsed.get("code")
        if isinstance(parsed_code, str) and parsed_code.strip():
            code = parsed_code.strip()
    return message, code


def quote(value: str) -> str:
    return parse.quote(value, safe="")
