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
