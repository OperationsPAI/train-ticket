from __future__ import annotations

from collections.abc import Mapping
import os
from typing import Any

import httpx


class DownstreamError(RuntimeError):
    def __init__(self, message: str, code: str | None = None) -> None:
        super().__init__(message)
        self.code = code or "DOWNSTREAM_UNAVAILABLE"


class DisruptionRecoveryClient:
    def __init__(self, base_url: str | None = None, timeout: float = 5.0) -> None:
        self.base_url = (base_url or os.getenv("DISRUPTION_RECOVERY_URL") or "http://disruption-recovery:8080").rstrip("/")
        self.timeout = timeout

    def report_missed_connection(self, body: Mapping[str, Any], idempotency_key: str, correlation_id: str) -> Mapping[str, Any]:
        try:
            with httpx.Client(timeout=self.timeout) as client:
                response = client.post(
                    f"{self.base_url}/api/v1/disruptions",
                    json=dict(body),
                    headers={"Accept": "application/json", "Content-Type": "application/json", "Idempotency-Key": idempotency_key, "X-Correlation-Id": correlation_id},
                )
        except httpx.HTTPError as exc:
            raise DownstreamError(str(exc), "HTTP_REQUEST_FAILED") from exc
        if response.status_code >= 500:
            raise DownstreamError(f"disruption-recovery returned {response.status_code}", f"HTTP_{response.status_code}")
        if response.status_code >= 400:
            raise DownstreamError(f"disruption-recovery rejected request {response.status_code}", f"HTTP_{response.status_code}")
        try:
            payload = response.json()
        except ValueError as exc:
            raise DownstreamError("disruption-recovery returned invalid JSON", "INVALID_JSON") from exc
        if not isinstance(payload, Mapping):
            raise DownstreamError("disruption-recovery returned invalid body", "INVALID_RESPONSE")
        return payload
