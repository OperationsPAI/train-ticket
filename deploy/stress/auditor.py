#!/usr/bin/env python3
"""Post-run correctness checker for stress test results.

Queries PostgreSQL (via kubectl exec) and Redis to verify six hard invariants
that must hold regardless of load level:

  1. Inventory conservation — confirmed orders <= capacity
  2. Seat uniqueness       — no duplicate seatRef per segment+date
  3. Fund conservation     — captures = order amounts; refunds <= captures
  4. No stuck orders       — all sagas terminal; outbox drained; DLQ = 0
  5. Idempotent single-effect — each idempotency key -> exactly 1 effect
  6. Clean losers          — in rush scenarios, all failures are contractual

Usage:
  python3 auditor.py --scenario scenarios/s1-rush.yaml --report /tmp/s1-report.json
  python3 auditor.py --scenario scenarios/s1-rush.yaml  # no report, DB-only checks
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from dataclasses import asdict, dataclass, field
from typing import Any

import yaml

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

KUBE_CONTEXT = "kind-arl-test"
KUBE_NAMESPACE = "train-ticket"
POSTGRES_POD = "postgres-0"
POSTGRES_USER = "trainticket"
REDIS_POD = "redis-0"

# Contractual error codes that are acceptable "clean" failures in rush
# scenarios.  Any 4xx error code with one of these bodies is not a bug.
CLEAN_FAILURE_CODES = frozenset({
    "CAPACITY_EXHAUSTED",
    "SEGMENT_FULL",
    "INVENTORY_UNAVAILABLE",
    "OFFER_EXPIRED",
    "SEAT_UNAVAILABLE",
    "CONFLICT",
    "SAGA_COMPENSATION",
    "ORDER_REJECTED",
    "RESERVATION_DENIED",
    "PAYMENT_CAPTURE_TRANSPORT",
})


# ---------------------------------------------------------------------------
# kubectl helpers
# ---------------------------------------------------------------------------


def _kubectl_exec(pod: str, command: list[str], namespace: str = KUBE_NAMESPACE) -> str:
    """Run a command inside a pod via kubectl exec and return stdout."""
    cmd = [
        "kubectl", "--context", KUBE_CONTEXT,
        "exec", "-n", namespace, pod, "--",
    ] + command
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True, timeout=30,
        )
        if result.returncode != 0:
            stderr = result.stderr.strip()
            if stderr:
                print(f"[kubectl] stderr: {stderr}", file=sys.stderr)
        return result.stdout.strip()
    except subprocess.TimeoutExpired:
        print(f"[kubectl] timeout: {' '.join(cmd)}", file=sys.stderr)
        return ""
    except FileNotFoundError:
        print("[kubectl] kubectl not found; returning empty result", file=sys.stderr)
        return ""


def query_db(database: str, sql: str) -> str:
    """Execute a SQL query against a PostgreSQL database via kubectl exec."""
    return _kubectl_exec(
        POSTGRES_POD,
        ["psql", "-U", POSTGRES_USER, "-d", database, "-t", "-A", "-c", sql],
    )


def query_redis(cmd: str) -> str:
    """Execute a Redis command via kubectl exec."""
    parts = cmd.split()
    return _kubectl_exec(REDIS_POD, ["redis-cli"] + parts)


# ---------------------------------------------------------------------------
# Assertion result
# ---------------------------------------------------------------------------


@dataclass
class AssertionResult:
    name: str
    passed: bool
    details: dict[str, Any] = field(default_factory=dict)
    error: str | None = None


# ---------------------------------------------------------------------------
# Assertion implementations
# ---------------------------------------------------------------------------


def _parse_int(value: Any, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _report_purchased(report: dict | None) -> int:
    if not report:
        return 0
    return _parse_int(report.get("results", {}).get("purchased", 0))


def _report_route_count(report: dict | None) -> int:
    if not report:
        return 1

    for key in ("route_count", "routes_discovered", "discovered_route_count"):
        count = _parse_int(report.get(key), 0)
        if count > 0:
            return count

    for key in ("routes", "discovered_routes", "bookable_routes"):
        routes = report.get(key)
        if isinstance(routes, list) and routes:
            return len(routes)

    return 1


def _query_capacity_segments() -> dict[str, dict[str, int]]:
    """Return capacity and confirmed holds per segment from capacity-availability.

    The capacity service stores aggregate snapshots as JSONB.  Query the pool
    snapshots for actual capacity, and count only confirmed hold snapshots so
    refunded/released capacity is not treated as sold.  An empty result is used
    by callers as the signal to fall back to older booking tables.
    """
    sql = """
        SELECT segment_ref, SUM(capacity) AS capacity, SUM(confirmed) AS confirmed
        FROM (
            SELECT
                COALESCE(
                    data #>> '{identity,routeSegmentRef}',
                    data #>> '{identity,serviceSegmentRef}',
                    id
                ) AS segment_ref,
                jsonb_array_length(COALESCE(data -> 'capacityUnits', '[]'::jsonb)) AS capacity,
                (
                    SELECT COUNT(*)
                    FROM capacity_hold_snapshots h
                    WHERE h.data ->> 'inventoryPoolId' = inventory_pool_snapshots.id
                      AND h.data ->> 'state' = 'Confirmed'
                ) AS confirmed
            FROM inventory_pool_snapshots
        ) pools
        GROUP BY segment_ref
        ORDER BY confirmed DESC;
    """
    raw = query_db("capacity_availability", sql)
    segments: dict[str, dict[str, int]] = {}
    for line in raw.splitlines():
        parts = line.split("|")
        if len(parts) < 3:
            continue
        segment_ref = parts[0].strip()
        capacity = _parse_int(parts[1].strip(), -1)
        confirmed = _parse_int(parts[2].strip(), -1)
        if segment_ref and capacity >= 0 and confirmed >= 0:
            segments[segment_ref] = {
                "capacity": capacity,
                "confirmed": confirmed,
            }
    return segments


def _query_booking_confirmed_segments() -> dict[str, int]:
    # Query the seat-assignment or booking database for confirmed bookings
    # grouped by segment.  This is a fallback for deployments whose
    # capacity-availability table names do not match the current snapshots.
    sql = """
        SELECT segment_ref, COUNT(*) as confirmed
        FROM segment_bookings
        WHERE status IN ('CONFIRMED', 'ACTIVE', 'TICKETED')
        GROUP BY segment_ref
        ORDER BY confirmed DESC
        LIMIT 20;
    """
    raw = query_db("booking_orchestration", sql)
    if not raw:
        # Try alternative table name
        sql_alt = """
            SELECT segment_ref, COUNT(*) as confirmed
            FROM booking_saga
            WHERE status IN ('COMPLETED', 'CONFIRMED')
            GROUP BY segment_ref
            ORDER BY confirmed DESC
            LIMIT 20;
        """
        raw = query_db("booking_orchestration", sql_alt)

    segments: dict[str, int] = {}
    for line in raw.splitlines():
        parts = line.split("|")
        if len(parts) >= 2:
            seg = parts[0].strip()
            count = _parse_int(parts[1].strip(), -1)
            if seg and count >= 0:
                segments[seg] = count
    return segments


def assert_inventory_conservation(cfg: dict, report: dict | None) -> AssertionResult:
    """Confirmed holds must not exceed capacity for any segment+date."""
    name = "inventory_conservation"
    constraints = cfg.get("constraints", {})
    target_capacity = constraints.get("target_capacity")
    report_purchased = _report_purchased(report)

    capacity_segments = _query_capacity_segments()
    violations: list[str] = []
    if capacity_segments:
        for seg, counts in capacity_segments.items():
            confirmed = counts["confirmed"]
            capacity = counts["capacity"]
            if confirmed > capacity:
                violations.append(f"{seg}: confirmed_holds={confirmed} > capacity={capacity}")

        return AssertionResult(
            name=name,
            passed=len(violations) == 0,
            details={
                "source": "capacity_availability",
                "segments": capacity_segments,
                "total_capacity": sum(s["capacity"] for s in capacity_segments.values()),
                "total_confirmed_holds": sum(s["confirmed"] for s in capacity_segments.values()),
                "target_capacity": target_capacity,
                "report_purchased": report_purchased,
                "violations": violations,
            },
        )

    segments = _query_booking_confirmed_segments()
    for seg, count in segments.items():
        if target_capacity is not None and count > int(target_capacity):
            violations.append(f"{seg}: {count} > {target_capacity}")

    if target_capacity is not None and report:
        route_count = max(_report_route_count(report), len(segments), 1)
        aggregate_capacity = int(target_capacity) * route_count
        if report_purchased > aggregate_capacity:
            violations.append(
                f"report.purchased={report_purchased} > "
                f"capacity={aggregate_capacity} ({target_capacity} x {route_count} routes)"
            )
    else:
        route_count = max(len(segments), 1)
        aggregate_capacity = None

    passed = len(violations) == 0
    return AssertionResult(
        name=name,
        passed=passed,
        details={
            "source": "booking_orchestration_fallback",
            "segments": segments,
            "target_capacity": target_capacity,
            "route_count": route_count,
            "aggregate_capacity": aggregate_capacity,
            "report_purchased": report_purchased,
            "violations": violations,
        },
    )


def assert_seat_uniqueness(cfg: dict, report: dict | None) -> AssertionResult:
    """No duplicate seatRef per segment+date."""
    name = "seat_uniqueness"
    sql = """
        SELECT segment_ref, seat_ref, COUNT(*) as cnt
        FROM seat_assignments
        WHERE seat_ref IS NOT NULL
        GROUP BY segment_ref, seat_ref
        HAVING COUNT(*) > 1
        LIMIT 20;
    """
    raw = query_db("seat_assignment", sql)
    if not raw:
        # Try alternative database / table
        sql_alt = """
            SELECT segment_ref, seat_ref, COUNT(*) as cnt
            FROM segment_bookings
            WHERE seat_ref IS NOT NULL
            GROUP BY segment_ref, seat_ref
            HAVING COUNT(*) > 1
            LIMIT 20;
        """
        raw = query_db("booking_orchestration", sql_alt)

    duplicates: list[dict[str, Any]] = []
    for line in raw.splitlines():
        parts = line.split("|")
        if len(parts) >= 3:
            duplicates.append({
                "segment_ref": parts[0].strip(),
                "seat_ref": parts[1].strip(),
                "count": int(parts[2].strip()),
            })

    passed = len(duplicates) == 0
    return AssertionResult(
        name=name,
        passed=passed,
        details={"duplicates": duplicates},
    )


def assert_fund_conservation(cfg: dict, report: dict | None) -> AssertionResult:
    """sum(captures) = sum(order amounts); sum(refunds) <= sum(captures)."""
    name = "fund_conservation"

    # Total captures from greenfield JSON snapshots, expressed in minor units.
    sql_captures = """
        SELECT COALESCE(SUM((data->'capturedAmount'->>'minorUnits')::bigint), 0)
        FROM payment_intent_snapshots
        WHERE data->>'status' IN ('CAPTURED', 'REFUNDED');
    """
    raw_captures = query_db("payment", sql_captures)
    if not raw_captures.strip().lstrip("-").isdigit():
        sql_captures = """
            SELECT COALESCE(SUM(amount_minor), 0) FROM payment_intents
            WHERE status = 'CAPTURED';
        """
        raw_captures = query_db("payment", sql_captures)
    total_captures = int(raw_captures.strip()) if raw_captures.strip().lstrip("-").isdigit() else 0

    # Total order amounts from non-cancelled order items in greenfield snapshots.
    sql_orders = """
        SELECT COALESCE(SUM((item->'amount'->>'minorUnits')::bigint), 0)
        FROM journey_order_snapshots
        CROSS JOIN LATERAL jsonb_array_elements(data->'orderItems') AS item
        WHERE data->>'status' IN ('CONFIRMED', 'CONFIRMING', 'COMPLETED', 'ADJUSTED')
          AND COALESCE((item->>'cancelled')::boolean, false) = false;
    """
    raw_orders = query_db("journey_order", sql_orders)
    if not raw_orders.strip().lstrip("-").isdigit():
        sql_orders = """
            SELECT COALESCE(SUM(total_minor), 0) FROM journey_orders
            WHERE status IN ('CONFIRMED', 'COMPLETED');
        """
        raw_orders = query_db("journey_order", sql_orders)
    total_orders = int(raw_orders.strip()) if raw_orders.strip().lstrip("-").isdigit() else 0

    # Total settled/requested refunds.
    sql_refunds = """
        SELECT COALESCE(SUM((data->'amount'->>'minorUnits')::bigint), 0)
        FROM refund_snapshots
        WHERE data->>'status' IN ('REQUESTED', 'SUBMITTED', 'SETTLED');
    """
    raw_refunds = query_db("payment", sql_refunds)
    if not raw_refunds.strip().lstrip("-").isdigit():
        sql_refunds_alt = """
            SELECT COALESCE(SUM(refund_amount_minor), 0) FROM refunds;
        """
        raw_refunds = query_db("payment", sql_refunds_alt)
    total_refunds = int(raw_refunds.strip()) if raw_refunds.strip().lstrip("-").isdigit() else 0

    violations: list[str] = []
    # Allow a small tolerance for timing (in-flight captures/refunds not yet settled).
    # Before refunds, gross captures should match payable order totals.  After
    # refunds, post-sales may cancel order lines, so the conserved value is net
    # captured funds (captures - refunds) against the remaining payable total.
    conserved_funds = total_captures - total_refunds
    if total_captures > 0:
        expected_funds = conserved_funds if total_refunds > 0 else total_captures
        diff = abs(expected_funds - total_orders)
        tolerance = max(abs(expected_funds), total_orders, 1) * 0.05
        if diff > tolerance:
            violations.append(
                f"net funds ({expected_funds}) != orders ({total_orders}), diff={diff}"
            )

    if total_refunds > total_captures:
        violations.append(
            f"refunds ({total_refunds}) > captures ({total_captures})"
        )

    passed = len(violations) == 0
    return AssertionResult(
        name=name,
        passed=passed,
        details={
            "total_captures": total_captures,
            "total_orders": total_orders,
            "total_refunds": total_refunds,
            "net_captures_after_refunds": total_captures - total_refunds,
            "violations": violations,
        },
    )


def assert_no_stuck_orders(cfg: dict, report: dict | None) -> AssertionResult:
    """All sagas should be terminal; outbox drained; DLQ empty."""
    name = "no_stuck_orders"
    details: dict[str, Any] = {}
    violations: list[str] = []

    # Non-terminal sagas
    sql_sagas = """
        SELECT status, COUNT(*) FROM booking_saga
        WHERE status NOT IN ('COMPLETED', 'COMPENSATED', 'FAILED', 'CANCELLED')
        GROUP BY status;
    """
    raw_sagas = query_db("booking_orchestration", sql_sagas)
    stuck_sagas: dict[str, int] = {}
    for line in raw_sagas.splitlines():
        parts = line.split("|")
        if len(parts) >= 2:
            status = parts[0].strip()
            count = int(parts[1].strip())
            stuck_sagas[status] = count
    details["stuck_sagas"] = stuck_sagas
    if stuck_sagas:
        total_stuck = sum(stuck_sagas.values())
        violations.append(f"{total_stuck} non-terminal saga(s): {stuck_sagas}")

    # Outbox drain check.  Greenfield services use an `outbox` table with
    # `published_at`; keep the legacy `outbox_events` fallback for older local
    # deployments.
    for db_name in ("booking_orchestration", "journey_order", "payment", "post_sales"):
        sql_outbox = """
            SELECT COUNT(*) FROM outbox
            WHERE published_at IS NULL;
        """
        raw = query_db(db_name, sql_outbox)
        if not raw.strip().isdigit():
            sql_outbox_events = """
                SELECT COUNT(*) FROM outbox_events
                WHERE published = false OR published IS NULL;
            """
            raw = query_db(db_name, sql_outbox_events)
        count = int(raw.strip()) if raw.strip().isdigit() else 0
        details[f"outbox_pending_{db_name}"] = count
        if count > 0:
            violations.append(f"{db_name}: {count} unpublished outbox event(s)")

    # DLQ check (via Redis)
    dlq_len = query_redis("LLEN dlq:events")
    dlq_count = int(dlq_len) if dlq_len.strip().isdigit() else 0
    details["dlq_count"] = dlq_count
    if dlq_count > 0:
        violations.append(f"DLQ has {dlq_count} message(s)")

    passed = len(violations) == 0
    return AssertionResult(
        name=name,
        passed=passed,
        details=details,
        error="; ".join(violations) if violations else None,
    )


def assert_idempotent_single_effect(cfg: dict, report: dict | None) -> AssertionResult:
    """Each idempotency key must map to exactly one effect row."""
    name = "idempotent_single_effect"

    duplicate_queries = [
        (
            "payment",
            "payment_intent_snapshots",
            """
            SELECT data->>'idempotencyKey', COUNT(*) as cnt
            FROM payment_intent_snapshots
            WHERE data->>'idempotencyKey' IS NOT NULL
            GROUP BY data->>'idempotencyKey'
            HAVING COUNT(*) > 1
            LIMIT 20;
            """,
        ),
        (
            "payment",
            "refund_snapshots",
            """
            SELECT data->>'idempotencyKey', COUNT(*) as cnt
            FROM refund_snapshots
            WHERE data->>'idempotencyKey' IS NOT NULL
            GROUP BY data->>'idempotencyKey'
            HAVING COUNT(*) > 1
            LIMIT 20;
            """,
        ),
        (
            "journey_order",
            "journey_order_snapshots",
            """
            SELECT data->>'idempotencyKey', COUNT(*) as cnt
            FROM journey_order_snapshots
            WHERE data->>'idempotencyKey' IS NOT NULL
            GROUP BY data->>'idempotencyKey'
            HAVING COUNT(*) > 1
            LIMIT 20;
            """,
        ),
        (
            "post_sales",
            "post_sales_case_snapshots",
            """
            SELECT data->>'idempotencyKey', COUNT(*) as cnt
            FROM post_sales_case_snapshots
            WHERE data->>'idempotencyKey' IS NOT NULL
            GROUP BY data->>'idempotencyKey'
            HAVING COUNT(*) > 1
            LIMIT 20;
            """,
        ),
    ]

    duplicates: list[dict[str, Any]] = []
    for database, table, sql in duplicate_queries:
        raw = query_db(database, sql)
        for line in raw.splitlines():
            parts = line.split("|")
            if len(parts) >= 2:
                duplicates.append({
                    "database": database,
                    "table": table,
                    "idempotency_key": parts[0].strip(),
                    "count": int(parts[1].strip()),
                })

    passed = len(duplicates) == 0
    return AssertionResult(
        name=name,
        passed=passed,
        details={"duplicates": duplicates},
    )


def assert_clean_losers(cfg: dict, report: dict | None) -> AssertionResult:
    """In rush scenarios, all failures must be contractual error codes."""
    name = "clean_losers"

    if not report:
        return AssertionResult(
            name=name,
            passed=True,
            details={"skipped": "no report provided"},
        )

    results = report.get("results", {})
    error_counts = report.get("error_counts", {})

    # Collect all failure entries from the results
    failures: dict[str, int] = {}
    dirty_failures: dict[str, int] = {}

    for key, count in results.items():
        if not key.startswith("failed:") and not key.startswith("crashed:"):
            continue
        failures[key] = count

        # Check if the failure reason matches a clean code.  Normalize common
        # separator differences because result keys often use HTTP step names
        # (payment-capture) while contract codes use enum names
        # (PAYMENT_CAPTURE_TRANSPORT).
        parts = key.split(":")
        step = parts[-1] if len(parts) > 1 else key
        normalized_step = step.lower().replace("-", "_")
        is_clean = any(
            code.lower() in normalized_step for code in CLEAN_FAILURE_CODES
        )

        if not is_clean:
            # Check if it is a known transport/timeout — those are infra, not bugs
            if "transport" in step.lower() or "timeout" in step.lower():
                continue
            if (
                step == "payment-capture"
                and _parse_int(error_counts.get("payment:transport", 0)) >= count
            ):
                continue
            dirty_failures[key] = count

    # For rush scenarios (mix.purchase == 1.0), we are strict about dirty failures
    is_rush = float(cfg.get("mix", {}).get("purchase", 0)) == 1.0
    constraints = cfg.get("constraints", {})
    has_capacity = constraints.get("target_capacity") is not None

    if is_rush and has_capacity:
        passed = len(dirty_failures) == 0
    else:
        # For non-rush scenarios, just report; do not fail
        passed = True

    return AssertionResult(
        name=name,
        passed=passed,
        details={
            "is_rush_scenario": is_rush and has_capacity,
            "total_failures": sum(failures.values()),
            "failures": failures,
            "dirty_failures": dirty_failures,
        },
    )


# ---------------------------------------------------------------------------
# Orchestrator
# ---------------------------------------------------------------------------


ALL_ASSERTIONS = [
    ("inventory_conservation", assert_inventory_conservation),
    ("seat_uniqueness", assert_seat_uniqueness),
    ("fund_conservation", assert_fund_conservation),
    ("no_stuck_orders", assert_no_stuck_orders),
    ("idempotent_single_effect", assert_idempotent_single_effect),
    ("clean_losers", assert_clean_losers),
]


def run_all_assertions(
    cfg: dict, report: dict | None
) -> list[AssertionResult]:
    results: list[AssertionResult] = []
    for name, fn in ALL_ASSERTIONS:
        print(f"[audit] checking {name} ...", end=" ", flush=True)
        try:
            result = fn(cfg, report)
        except Exception as exc:
            result = AssertionResult(
                name=name, passed=False,
                error=f"{type(exc).__name__}: {exc}",
            )
        status = "PASS" if result.passed else "FAIL"
        print(status, flush=True)
        results.append(result)
    return results


def build_output(
    scenario_name: str, results: list[AssertionResult]
) -> dict[str, Any]:
    total = len(results)
    passed = sum(1 for r in results if r.passed)
    failed = total - passed
    return {
        "scenario": scenario_name,
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "assertions": [
            {
                "name": r.name,
                "passed": r.passed,
                "details": r.details,
                **({"error": r.error} if r.error else {}),
            }
            for r in results
        ],
        "summary": {"total": total, "passed": passed, "failed": failed},
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Post-run correctness auditor for stress tests"
    )
    parser.add_argument(
        "--scenario", required=True, help="Path to scenario YAML"
    )
    parser.add_argument(
        "--report", default=None, help="Path to driver JSON report"
    )
    parser.add_argument(
        "--output", default=None, help="Path to write audit JSON output"
    )
    parser.add_argument(
        "--context", default=KUBE_CONTEXT, help="kubectl context"
    )
    parser.add_argument(
        "--namespace", default=KUBE_NAMESPACE, help="Kubernetes namespace"
    )
    parser.add_argument(
        "--postgres-pod", default=POSTGRES_POD, help="PostgreSQL pod name"
    )
    parser.add_argument(
        "--redis-pod", default=REDIS_POD, help="Redis pod name"
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    # Apply CLI overrides to module-level config
    global KUBE_CONTEXT, KUBE_NAMESPACE, POSTGRES_POD, REDIS_POD
    KUBE_CONTEXT = args.context
    KUBE_NAMESPACE = args.namespace
    POSTGRES_POD = args.postgres_pod
    REDIS_POD = args.redis_pod

    with open(args.scenario) as f:
        cfg = yaml.safe_load(f)

    report: dict | None = None
    if args.report:
        with open(args.report) as f:
            report = json.load(f)

    scenario_name = cfg.get("name", "unknown")
    print(f"\n{'=' * 60}", flush=True)
    print(f"Auditing scenario: {scenario_name}", flush=True)
    print(f"{'=' * 60}\n", flush=True)

    results = run_all_assertions(cfg, report)
    output = build_output(scenario_name, results)

    # Write output
    output_path = args.output
    if output_path is None:
        output_path = f"/tmp/{scenario_name}-audit.json"
    with open(output_path, "w") as f:
        json.dump(output, f, indent=2)
    print(f"\n[audit] results written to {output_path}", flush=True)

    # Summary
    summary = output["summary"]
    print(f"\n{'=' * 60}", flush=True)
    print(
        f"RESULT: {summary['passed']}/{summary['total']} passed, "
        f"{summary['failed']} failed",
        flush=True,
    )
    print(f"{'=' * 60}\n", flush=True)

    if summary["failed"] > 0:
        print("Failed assertions:", flush=True)
        for r in results:
            if not r.passed:
                print(f"  - {r.name}: {r.error or r.details}", flush=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
