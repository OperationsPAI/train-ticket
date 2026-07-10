#!/usr/bin/env python3
"""Performance oracle — collects and reports system-level metrics.

Run after a stress test to gather PG stats, Redis backlog, pod resource
usage, and service latency profiles into a single JSON snapshot.

Usage:
  python3 oracle.py --output /tmp/metrics.json
  python3 oracle.py --output /tmp/metrics.json --postgres-pod postgres-xxx --redis-pod redis-xxx
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from typing import Any

KUBE_CONTEXT = "kind-arl-test"
KUBE_NAMESPACE = "train-ticket"
POSTGRES_POD = "postgres-0"
REDIS_POD = "redis-0"


def _kubectl_exec(pod: str, cmd: list[str]) -> str:
    full = [
        "kubectl", "--context", KUBE_CONTEXT, "-n", KUBE_NAMESPACE,
        "exec", "-i", pod, "--",
    ] + cmd
    try:
        result = subprocess.run(full, capture_output=True, text=True, timeout=30)
        if result.returncode != 0 and result.stderr:
            print(f"[oracle] {pod} stderr: {result.stderr.strip()}", file=sys.stderr)
        return result.stdout.strip()
    except subprocess.TimeoutExpired:
        return ""


def query_db(database: str, sql: str) -> str:
    return _kubectl_exec(POSTGRES_POD, [
        "psql", "-U", "trainticket", "-d", database, "-t", "-A", "-c", sql
    ])


def query_redis(*parts: str) -> str:
    return _kubectl_exec(REDIS_POD, ["redis-cli"] + list(parts))


def collect_pg_stats() -> dict:
    """pg_stat_statements top queries by total_time."""
    raw = query_db("postgres", """
        SELECT json_agg(row_to_json(t)) FROM (
            SELECT query, calls, total_exec_time::numeric(12,2) as total_ms,
                   mean_exec_time::numeric(12,2) as mean_ms,
                   max_exec_time::numeric(12,2) as max_ms,
                   rows
            FROM pg_stat_statements
            WHERE query NOT LIKE '%pg_stat%'
            ORDER BY total_exec_time DESC LIMIT 20
        ) t
    """)
    try:
        return {"top_queries": json.loads(raw) if raw else []}
    except json.JSONDecodeError:
        return {"top_queries": [], "raw": raw[:500]}


def collect_pg_connections() -> dict:
    """Active connections per database."""
    raw = query_db("postgres", """
        SELECT json_agg(row_to_json(t)) FROM (
            SELECT datname, count(*) as connections,
                   count(*) FILTER (WHERE state = 'active') as active,
                   count(*) FILTER (WHERE state = 'idle') as idle
            FROM pg_stat_activity
            WHERE datname IS NOT NULL
            GROUP BY datname ORDER BY connections DESC
        ) t
    """)
    try:
        return {"connections": json.loads(raw) if raw else []}
    except json.JSONDecodeError:
        return {"connections": [], "raw": raw[:300]}


def collect_pg_table_sizes() -> dict:
    """Table sizes across all service databases."""
    databases = ["payment", "booking_orchestration", "journey_order",
                 "capacity_availability", "finance_settlement", "invoicing"]
    sizes = {}
    for db in databases:
        raw = query_db(db, """
            SELECT json_agg(row_to_json(t)) FROM (
                SELECT tablename, pg_total_relation_size(schemaname||'.'||tablename) as bytes
                FROM pg_tables WHERE schemaname = 'public'
                ORDER BY bytes DESC LIMIT 5
            ) t
        """)
        try:
            sizes[db] = json.loads(raw) if raw else []
        except (json.JSONDecodeError, TypeError):
            pass
    return {"table_sizes": sizes}


def collect_redis_streams() -> dict:
    """XLEN and consumer group lag for all event streams."""
    streams_raw = query_redis("KEYS", "events:*")
    if not streams_raw:
        return {"streams": {}}
    streams = {}
    for stream in sorted(streams_raw.split("\n")):
        stream = stream.strip()
        if not stream or ":dlq" in stream:
            continue
        xlen = query_redis("XLEN", stream)
        groups_raw = query_redis("XINFO", "GROUPS", stream)
        groups = []
        if groups_raw:
            lines = groups_raw.split("\n")
            current = {}
            for line in lines:
                line = line.strip()
                if line == "name":
                    if current:
                        groups.append(current)
                    current = {}
                elif current is not None:
                    if not current.get("_last_key"):
                        current["_last_key"] = line
                    else:
                        key = current.pop("_last_key")
                        current[key] = line
            if current:
                groups.append(current)
        streams[stream] = {
            "length": int(xlen) if xlen.isdigit() else 0,
            "groups": [{k: v for k, v in g.items() if not k.startswith("_")} for g in groups],
        }
    return {"streams": streams}


def collect_redis_dlqs() -> dict:
    """DLQ lengths."""
    dlq_raw = query_redis("KEYS", "events:*:dlq")
    if not dlq_raw:
        return {"dlqs": {}}
    dlqs = {}
    for dlq in sorted(dlq_raw.split("\n")):
        dlq = dlq.strip()
        if not dlq:
            continue
        xlen = query_redis("XLEN", dlq)
        dlqs[dlq] = int(xlen) if xlen.isdigit() else 0
    return {"dlqs": dlqs}


def collect_pod_status() -> dict:
    """Pod status and restart counts."""
    full = [
        "kubectl", "--context", KUBE_CONTEXT, "-n", KUBE_NAMESPACE,
        "get", "pods", "--no-headers",
        "-o", "custom-columns=NAME:.metadata.name,STATUS:.status.phase,RESTARTS:.status.containerStatuses[0].restartCount,AGE:.status.startTime",
    ]
    try:
        result = subprocess.run(full, capture_output=True, text=True, timeout=15)
        pods = []
        for line in result.stdout.strip().split("\n"):
            parts = line.split()
            if len(parts) >= 3:
                pods.append({
                    "name": parts[0],
                    "status": parts[1],
                    "restarts": int(parts[2]) if parts[2].isdigit() else 0,
                })
        return {"pods": pods, "total": len(pods),
                "running": sum(1 for p in pods if p["status"] == "Running"),
                "total_restarts": sum(p["restarts"] for p in pods)}
    except Exception:
        return {"pods": [], "total": 0, "running": 0, "total_restarts": 0}


def collect_outbox_status() -> dict:
    """Outbox drain status across services."""
    databases = ["payment", "booking_orchestration", "journey_order",
                 "capacity_availability", "finance_settlement"]
    outbox = {}
    for db in databases:
        raw = query_db(db, "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL")
        if raw and raw.strip().isdigit():
            outbox[db] = {"unpublished": int(raw.strip())}
        else:
            total = query_db(db, "SELECT COUNT(*) FROM outbox")
            outbox[db] = {"total": int(total.strip()) if total.strip().isdigit() else -1}
    return {"outbox": outbox}


def main() -> None:
    global KUBE_CONTEXT, KUBE_NAMESPACE, POSTGRES_POD, REDIS_POD

    parser = argparse.ArgumentParser(description="Performance metrics oracle")
    parser.add_argument("--output", "-o", default="/tmp/oracle-metrics.json")
    parser.add_argument("--context", default=KUBE_CONTEXT)
    parser.add_argument("--namespace", default=KUBE_NAMESPACE)
    parser.add_argument("--postgres-pod", default=POSTGRES_POD)
    parser.add_argument("--redis-pod", default=REDIS_POD)
    args = parser.parse_args()

    KUBE_CONTEXT = args.context
    KUBE_NAMESPACE = args.namespace
    POSTGRES_POD = args.postgres_pod
    REDIS_POD = args.redis_pod

    print("[oracle] collecting metrics...", flush=True)
    metrics = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "cluster": {"context": KUBE_CONTEXT, "namespace": KUBE_NAMESPACE},
    }

    print("[oracle] pod status...", flush=True)
    metrics["pods"] = collect_pod_status()

    print("[oracle] redis streams...", flush=True)
    metrics["redis"] = {**collect_redis_streams(), **collect_redis_dlqs()}

    print("[oracle] postgres connections...", flush=True)
    metrics["postgres"] = {**collect_pg_connections(), **collect_pg_table_sizes()}

    print("[oracle] outbox status...", flush=True)
    metrics["outbox"] = collect_outbox_status()

    with open(args.output, "w") as f:
        json.dump(metrics, f, indent=2)
    print(f"[oracle] written to {args.output}", flush=True)

    # Summary
    pods = metrics["pods"]
    print(f"\n=== Oracle Summary ===")
    print(f"Pods: {pods['running']}/{pods['total']} running, {pods['total_restarts']} total restarts")

    dlqs = metrics["redis"].get("dlqs", {})
    total_dlq = sum(dlqs.values())
    print(f"DLQ total: {total_dlq} ({'CLEAN' if total_dlq == 0 else 'WARNING'})")

    outbox = metrics.get("outbox", {}).get("outbox", {})
    unpub = sum(v.get("unpublished", 0) for v in outbox.values())
    print(f"Outbox unpublished: {unpub} ({'DRAINED' if unpub == 0 else 'PENDING'})")

    streams = metrics["redis"].get("streams", {})
    lagging = []
    for sname, sinfo in streams.items():
        for g in sinfo.get("groups", []):
            lag = g.get("lag", "0")
            if str(lag).isdigit() and int(lag) > 0:
                lagging.append(f"{sname}/{g.get('name','?')}={lag}")
    print(f"Consumer lag: {len(lagging)} groups lagging" if lagging else "Consumer lag: all caught up")
    if lagging:
        for l in lagging[:5]:
            print(f"  {l}")
    print("=" * 22)


if __name__ == "__main__":
    main()
