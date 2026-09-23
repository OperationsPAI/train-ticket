import argparse
import hashlib
import json
import subprocess
from pathlib import Path


DATABASES = (
    "booking_orchestration",
    "finance_settlement",
    "journey_order",
    "offer_management",
)

QUERY = """
SELECT jsonb_build_object(
    'database', current_database(),
    'captured_at', statement_timestamp(),
    'server_version', current_setting('server_version'),
    'read_only', current_setting('transaction_read_only'),
    'sample_limit', 12000,
    'table_stats', (SELECT jsonb_build_object('pages', relpages, 'rows', reltuples)
                    FROM pg_class WHERE oid = 'public.outbox'::regclass),
    'indexes', (SELECT jsonb_agg(indexdef ORDER BY indexname)
                FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'outbox'),
    'rows', (SELECT jsonb_agg(to_jsonb(sample) ORDER BY seq) FROM (
        SELECT seq, event_id, stream, envelope, created_at, published_at
        FROM public.outbox ORDER BY seq DESC LIMIT 12000
    ) sample)
);
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    for database in DATABASES:
        result = subprocess.run(
            ["kubectl", "-n", "train-ticket", "exec", "deploy/postgres-core", "--",
             "env", "PGOPTIONS=-c statement_timeout=5000 -c default_transaction_read_only=on",
             "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "trainticket",
             "-d", database, "-c", QUERY],
            check=True, capture_output=True, text=True, timeout=15,
        )
        recorded = json.loads(result.stdout)
        assert recorded["database"] == database and recorded["read_only"] == "on"
        assert recorded["rows"], f"No recorded rows for {database}"
        raw = result.stdout.encode()
        (args.output / f"{database}.json").write_bytes(raw)
        print(json.dumps({"database": database, "rows": len(recorded["rows"]),
                          "captured_at": recorded["captured_at"],
                          "sha256": hashlib.sha256(raw).hexdigest()}), flush=True)


if __name__ == "__main__":
    main()
