from __future__ import annotations

from json import JSONDecodeError
import json
import os
from urllib import error, parse, request

from fare_pricing.application.service import EligibilityCertificatePort


class HttpEligibilityCertificateAdapter(EligibilityCertificatePort):
    def __init__(self, base_url: str | None = None, timeout: float | None = None) -> None:
        self.base_url = (base_url or os.getenv("IDENTITY_VERIFICATION_URL", "http://identity-verification:8080")).rstrip("/")
        self.timeout = timeout or float(os.getenv("DOWNSTREAM_TIMEOUT_SECONDS", "10"))

    def has_active_certificate(self, traveler_id: str, eligibility_type: str, journey_date: str, product_code: str) -> bool:
        query = parse.urlencode({"travelerId": traveler_id, "eligibilityType": eligibility_type, "journeyDate": journey_date, "productCode": product_code, "limit": 1, "offset": 0})
        req = request.Request(self.base_url + "/api/v1/identity-verification/eligibility-certificates?" + query, method="GET", headers={"Accept": "application/json"})
        try:
            with request.urlopen(req, timeout=self.timeout) as response:  # noqa: S310 in-cluster configured URL
                data = json.loads(response.read().decode("utf-8") or "{}")
                return int(data.get("total") or 0) > 0
        except (error.URLError, JSONDecodeError, ValueError):
            return False
