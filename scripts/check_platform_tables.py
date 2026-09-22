import re
import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
SERVICES = REPOSITORY_ROOT / "services"

# The relay in every platform kit sweeps these three tables unconditionally, so
# a service whose migrations create some of them and not others fails that
# sweep on every pass. corporate-travel created processed_events and outbox but
# not idempotency_records, and the deployed cluster logged 946 occurrences of
# `relation "idempotency_records" does not exist` in one window.
PLATFORM_TABLES = ("outbox", "processed_events", "idempotency_records")


def creates_table(sql: str, table: str) -> bool:
    return re.search(rf"CREATE\s+TABLE\s+(IF\s+NOT\s+EXISTS\s+)?{table}\b", sql, re.IGNORECASE) is not None


def main() -> int:
    failures: list[str] = []
    for service in sorted(path for path in SERVICES.iterdir() if path.is_dir()):
        migrations = service / "migrations"
        if not migrations.is_dir():
            continue
        sql = "\n".join(path.read_text() for path in sorted(migrations.glob("*.sql")))
        present = [table for table in PLATFORM_TABLES if creates_table(sql, table)]
        if not present:
            continue
        missing = [table for table in PLATFORM_TABLES if table not in present]
        if missing:
            failures.append(
                f"{service.name}: creates {', '.join(present)} but not {', '.join(missing)}; "
                "the relay sweeps all three"
            )

    for failure in failures:
        print(failure)
    if failures:
        print(f"\n{len(failures)} services would fail their retention sweep on a missing table.")
        return 1
    print("every service that uses the platform tables creates all of them")
    return 0


if __name__ == "__main__":
    sys.exit(main())
