from __future__ import annotations

import json
import os
from urllib import error, parse, request

from fare_pricing.application.service import EligibilityCertificatePort, EligibilityUnavailableError
from train_ticket_platform.outbound import traced_urllib_request


class HttpEligibilityCertificateAdapter(EligibilityCertificatePort):
    def __init__(self, base_url: str | None = None, timeout: float | None = None) -> None:
        self.base_url = (base_url or os.getenv("IDENTITY_VERIFICATION_URL", "http://identity-verification:8080")).rstrip("/")
        self.timeout = timeout or float(os.getenv("DOWNSTREAM_TIMEOUT_SECONDS", "10"))

    def active_certificate_id(self, traveler_id: str, eligibility_type: str, journey_date: str, product_code: str) -> str | None:
        query = parse.urlencode({"travelerId": traveler_id, "eligibilityType": eligibility_type, "journeyDate": journey_date, "productCode": product_code, "limit": 1, "offset": 0})
        # traced_urllib_request, not request.Request: the platform kit attaches
        # the active span's W3C traceparent so identity-verification's server
        # span joins this trace instead of starting its own.
        req = traced_urllib_request(self.base_url + "/api/v1/identity-verification/eligibility-certificates?" + query, method="GET", headers={"Accept": "application/json"})
        try:
            with request.urlopen(req, timeout=self.timeout) as response:
                data = json.load(response)
        except (error.URLError, ValueError) as exc:
            raise EligibilityUnavailableError("Eligibility certificate lookup unavailable") from exc
        items = data["items"]
        if not items:
            return None
        return str(items[0]["eligibilityCertificateId"])
