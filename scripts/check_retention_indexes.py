import re
import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
SERVICES = REPOSITORY_ROOT / "services"

# Every platform table the outbox relay sweeps, and the column each sweep
# filters on. A service that creates the table owes an index on that column:
# without one the sweep plans a Seq Scan over the whole table, which is how
# traveler_profile reached 12811917 idempotency_records in 7855 MB with
# 12792783 of them past retention and the oldest 3 days 18 hours old.
SWEPT_TABLES = {
    "processed_events": "processed_at",
    "idempotency_records": "created_at",
}


def creates_table(sql: str, table: str) -> bool:
    return re.search(rf"CREATE\s+TABLE\s+(IF\s+NOT\s+EXISTS\s+)?{table}\b", sql, re.IGNORECASE) is not None


def indexes_column(sql: str, table: str, column: str) -> bool:
    pattern = rf"CREATE\s+(UNIQUE\s+)?INDEX\s+(CONCURRENTLY\s+)?(IF\s+NOT\s+EXISTS\s+)?\S+\s+ON\s+{table}\s*\(\s*{column}\b"
    return re.search(pattern, sql, re.IGNORECASE) is not None


def main() -> int:
    failures: list[str] = []
    for service in sorted(path for path in SERVICES.iterdir() if path.is_dir()):
        migrations = service / "migrations"
        if not migrations.is_dir():
            continue
        sql = "\n".join(path.read_text() for path in sorted(migrations.glob("*.sql")))
        for table, column in SWEPT_TABLES.items():
            if creates_table(sql, table) and not indexes_column(sql, table, column):
                failures.append(f"{service.name}: {table} is swept by {column} with no index on it")

    for failure in failures:
        print(failure)
    if failures:
        print(f"\n{len(failures)} retention sweeps would plan a Seq Scan.")
        return 1
    print("every swept table indexes the column its retention sweep filters on")
    return 0


if __name__ == "__main__":
    sys.exit(main())
