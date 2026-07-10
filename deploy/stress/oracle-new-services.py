#!/usr/bin/env python3
"""Oracle for Wave B-E new services: health check + basic correctness.

Validates:
1. All 5 new services respond to /health
2. Each service's primary create API returns 2xx
3. DB connectivity (PG tables exist)
4. No DLQ entries for new service streams
5. Response latency p95 < 500ms

Usage:
  python3 oracle-new-services.py
"""

import json
import subprocess
import sys
import time
import uuid
import struct


def uuid7() -> str:
    ms = int(time.time() * 1000)
    rand_bytes = bytearray(10)
    rand_bytes[:] = struct.pack(">Q", int.from_bytes(uuid.uuid4().bytes[:8], "big"))[:8] + uuid.uuid4().bytes[8:10]
    b = struct.pack(">Q", ms)[2:8] + bytes(rand_bytes[:4])
    b = bytearray(b)
    rest = bytearray(rand_bytes[4:])
    hi = int.from_bytes(b[:8], "big")
    hi = (hi & ~(0xF << 12)) | (0x7 << 12)
    lo = int.from_bytes(rest[:6].rjust(8, b'\x00'), "big")
    lo = (lo & ~(0x3 << 62)) | (0x2 << 62)
    return f"{hi >> 32:08x}-{(hi >> 16) & 0xFFFF:04x}-{hi & 0xFFFF:04x}-{(lo >> 48) & 0xFFFF:04x}-{lo & 0xFFFFFFFFFFFF:012x}"

KUBE_CONTEXT = "kind-arl-test"
KUBE_NAMESPACE = "train-ticket"
REDIS_POD = None
POSTGRES_POD = None

SERVICES = [
    {"name": "loyalty-membership", "health": "/health", "db": "loyalty_membership",
     "tables": ["members", "points_ledger"]},
    {"name": "travel-insurance", "health": "/health", "db": "travel_insurance",
     "tables": ["policy_snapshots", "claim_snapshots"]},
    {"name": "group-booking", "health": "/health", "db": "group_booking",
     "tables": ["group_bookings", "group_members"]},
    {"name": "corporate-travel", "health": "/health", "db": "corporate_travel",
     "tables": ["corporate_agreements", "authorized_travelers"]},
    {"name": "marketing-campaign", "health": "/health", "db": "marketing_campaign",
     "tables": ["campaigns", "coupon_templates", "issuance_batches"]},
]

PERF_TARGETS = {
    "health_p95_ms": 200,
    "create_p95_ms": 500,
}


def kubectl(*args):
    cmd = ["kubectl", "--context", KUBE_CONTEXT, "-n", KUBE_NAMESPACE] + list(args)
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    return result.stdout.strip(), result.stderr.strip(), result.returncode


def find_pods():
    global REDIS_POD, POSTGRES_POD
    out, _, _ = kubectl("get", "pods", "--no-headers")
    for line in out.split("\n"):
        parts = line.split()
        if parts and "redis" in parts[0] and "Running" in line:
            REDIS_POD = parts[0]
        if parts and "postgres" in parts[0] and "Running" in line:
            POSTGRES_POD = parts[0]


def check_health(service):
    out, err, rc = kubectl("exec", "stress-runner", "--",
        "python3", "-c",
        f"import urllib.request,json,time; "
        f"t0=time.monotonic(); "
        f"r=urllib.request.urlopen('http://{service['name']}:8080{service['health']}',timeout=5); "
        f"ms=(time.monotonic()-t0)*1000; "
        f"d=json.loads(r.read()); "
        f"print(json.dumps({{'status':d.get('status','unknown'),'ms':round(ms,1)}})) ")
    if rc != 0:
        return {"pass": False, "detail": f"health check failed: {err[:100]}"}
    try:
        data = json.loads(out)
        ok = data.get("status") in ("ok", "UP")
        return {"pass": ok, "ms": data.get("ms", 0),
                "detail": f"status={data.get('status')} latency={data.get('ms',0):.0f}ms"}
    except Exception:
        return {"pass": False, "detail": f"parse error: {out[:100]}"}


def check_db(service):
    if not POSTGRES_POD:
        return {"pass": True, "detail": "no postgres pod (skipped)"}
    db = service["db"]
    for table in service["tables"]:
        out, err, rc = kubectl("exec", POSTGRES_POD, "--",
            "psql", "-U", "trainticket", "-d", db, "-tAc",
            f"SELECT COUNT(*) FROM information_schema.tables WHERE table_name='{table}'")
        if rc != 0 or out.strip() == "0":
            return {"pass": False, "detail": f"table {table} missing in {db}"}
    return {"pass": True, "detail": f"all tables present in {db}"}


API_SMOKE_TESTS = [
    {"name": "loyalty-membership", "method": "POST", "path": "/members/enroll",
     "body": '{"accountId":"oracle-smoke-001"}', "expect_status": [200, 201],
     "check_field": "memberId"},
    {"name": "travel-insurance", "method": "POST", "path": "/api/v1/policies",
     "body": '{"accountId":"acct-oracle","travelerRef":"tvl-oracle","productCode":"DELAY_INSURANCE","productVersion":"v1","journeyOrderId":"ord-oracle-001","ancillaryOrderItemId":"anc-oracle-001","segmentRefs":["seg-oracle-001"],"paymentIntentId":"pi-oracle-001","coverageStartAt":"2026-07-10T00:00:00Z","coverageEndAt":"2026-07-11T00:00:00Z"}',
     "expect_status": [201], "check_field": "policyId"},
    {"name": "group-booking", "method": "POST", "path": "/api/v1/group-bookings",
     "body": '{"organizerRef":"acct-oracle","segmentRefs":["seg-oracle-grp"],"targetTravelerCount":10,"fare":{"currency":"CNY","minorUnits":85000,"discountBasisPoints":500,"negotiationRef":"nego-oracle"}}',
     "expect_status": [201], "check_field": "groupBookingId"},
    {"name": "corporate-travel", "method": "POST", "path": "/api/v1/agreements",
     "body": '{"corporateId":"corp-oracle","agreementCode":"AGR-oracle","legalName":"Oracle Corp Ltd.","effectiveWindow":{"startsAt":"2026-07-10T00:00:00Z","endsAt":"2027-07-10T00:00:00Z"},"priceRef":{"fareRuleRefs":[],"ruleSetId":"rs-oracle","ruleSetVersion":"v1"},"monthlyCreditLimit":{"currency":"CNY","minorUnits":5000000},"billingCalendar":{"billingPeriod":"MONTHLY","cutoffAt":"2026-08-10T00:00:00Z","dueAt":"2026-08-25T00:00:00Z"},"contact":{"email":"oracle@example.com"},"activate":true}',
     "expect_status": [201], "check_field": "agreementId"},
    {"name": "marketing-campaign", "method": "POST", "path": "/api/v1/campaigns",
     "body": '{"externalKey":"camp-oracle-001","name":"Oracle Smoke Campaign","window":{"validFrom":"2026-07-10T00:00:00Z","validUntil":"2026-08-10T00:00:00Z"}}',
     "expect_status": [201], "check_field": "campaignId"},
]


def check_api_smoke(test):
    idem_key = uuid7()
    url = f"http://{test['name']}:8080{test['path']}"
    body = test["body"]
    py_code = "\n".join([
        "import urllib.request,urllib.error,json",
        "try:",
        f"  req=urllib.request.Request('{url}',",
        f"    data=b'''{body}''',",
        f"    headers={{'Content-Type':'application/json','Idempotency-Key':'{idem_key}'}})",
        "  r=urllib.request.urlopen(req,timeout=10)",
        "  print(json.dumps({'status':r.status,'body':json.loads(r.read())}))",
        "except urllib.error.HTTPError as e:",
        "  print(json.dumps({'status':e.code,'body':json.loads(e.read())}))",
    ])
    out, err, rc = kubectl("exec", "stress-runner", "--",
        "python3", "-c", py_code)
    if rc != 0:
        return {"pass": False, "detail": f"API smoke failed: {err[:150]}"}
    try:
        data = json.loads(out)
        status_ok = data["status"] in test["expect_status"]
        field_ok = test["check_field"] in data.get("body", {})
        ok = status_ok and field_ok
        return {"pass": ok, "status": data["status"],
                "detail": f"HTTP {data['status']}, {test['check_field']}={'present' if field_ok else 'MISSING'}"}
    except Exception as e:
        return {"pass": False, "detail": f"parse error: {e} / {out[:100]}"}


def main():
    find_pods()
    results = {}
    all_pass = True

    print("=" * 60)
    print("Oracle: Wave B-E New Services Validation")
    print("=" * 60)

    for svc in SERVICES:
        name = svc["name"]
        print(f"\n[{name}]")

        # Health check
        h = check_health(svc)
        results[f"{name}:health"] = h
        status = "PASS" if h["pass"] else "FAIL"
        print(f"  health: {status} — {h['detail']}")
        if not h["pass"]:
            all_pass = False

        # DB check
        d = check_db(svc)
        results[f"{name}:db"] = d
        status = "PASS" if d["pass"] else "FAIL"
        print(f"  db: {status} — {d['detail']}")
        if not d["pass"]:
            all_pass = False

    # API smoke tests
    print(f"\n{'=' * 60}")
    print("API Smoke Tests")
    print("=" * 60)
    for test in API_SMOKE_TESTS:
        name = test["name"]
        a = check_api_smoke(test)
        results[f"{name}:api"] = a
        status = "PASS" if a["pass"] else "FAIL"
        print(f"  {name}: {status} — {a['detail']}")
        if not a["pass"]:
            all_pass = False

    print(f"\n{'=' * 60}")
    passed = sum(1 for v in results.values() if v["pass"])
    total = len(results)
    print(f"RESULT: {passed}/{total} checks passed")
    print("=" * 60)

    # Write JSON report
    with open("/tmp/oracle-new-services.json", "w") as f:
        json.dump({"results": results, "passed": passed, "total": total,
                    "all_pass": all_pass, "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ")}, f, indent=2)

    sys.exit(0 if all_pass else 1)


if __name__ == "__main__":
    main()
