from __future__ import annotations

from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass
import json
import os
from pathlib import Path
import threading
import time
from typing import Any, Protocol

from .events import EventEnvelope
from .idempotency import IdempotencyRecord, IdempotencyStore
from .messaging import MAXLEN, stream_for_producer


def _jsonb_payload(value: Any) -> Any:
    try:
        from psycopg.types.json import Jsonb

        return Jsonb(value)
    except ImportError:  # pragma: no cover - psycopg optional in unit tests
        return json.dumps(value, separators=(",", ":"))


class StorageError(RuntimeError):
    """Base class for durable storage failures."""


class MigrationError(StorageError):
    """Raised when a migration cannot be applied."""


class OptimisticConcurrencyError(StorageError):
    """Raised when an optimistic update affects no rows."""


@dataclass(frozen=True, slots=True)
class DatabaseConfig:
    url: str

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "DatabaseConfig | None":
        source = env or os.environ
        url = source.get("DATABASE_URL", "").strip()
        return cls(url) if url else None


class ConnectionPool(Protocol):
    def connection(self) -> Any: ...

    def close(self) -> None: ...


class DatabasePool:
    """Small psycopg3 pool wrapper driven by the canonical DATABASE_URL."""

    def __init__(self, config: DatabaseConfig | str, *, min_size: int = 1, max_size: int = 10, open: bool = True) -> None:
        self.config = config if isinstance(config, DatabaseConfig) else DatabaseConfig(config)
        try:
            from psycopg_pool import ConnectionPool as PsycopgConnectionPool
        except ImportError as exc:  # pragma: no cover - optional dependency
            raise StorageError("psycopg_pool is not installed") from exc
        import os
        pool_max = int(os.environ.get("PG_MAX_POOL_SIZE", str(max_size)))
        self._pool = PsycopgConnectionPool(
            self.config.url, min_size=min_size, max_size=pool_max, open=open,
            max_idle=300.0, max_lifetime=600.0, check=PsycopgConnectionPool.check_connection,
        )

    def connection(self) -> Any:
        return self._pool.connection()

    def close(self) -> None:
        self._pool.close()


class ReadinessGate:
    def __init__(self) -> None:
        self._ready = False
        self._failure: str | None = None
        self._lock = threading.Lock()

    @property
    def ready(self) -> bool:
        with self._lock:
            return self._ready

    @property
    def failure(self) -> str | None:
        with self._lock:
            return self._failure

    def mark_ready(self) -> None:
        with self._lock:
            self._ready = True
            self._failure = None

    def mark_failed(self, reason: str) -> None:
        with self._lock:
            self._ready = False
            self._failure = reason


def run_migrations(pool: ConnectionPool, migrations_dir: str | Path, readiness: ReadinessGate | None = None) -> None:
    """Apply ordered service SQL migrations and record schema_migrations."""

    path = Path(migrations_dir)
    migrations = sorted(path.glob("[0-9][0-9][0-9]_*.sql")) if path.exists() else []
    try:
        with pool.connection() as conn:
            with conn.transaction():
                conn.execute(
                    "CREATE TABLE IF NOT EXISTS schema_migrations ("
                    "version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())"
                )
                for migration in migrations:
                    version = migration.name
                    already = conn.execute("SELECT 1 FROM schema_migrations WHERE version = %s", (version,)).fetchone()
                    if already:
                        continue
                    sql = migration.read_text(encoding="utf-8")
                    if sql.strip():
                        conn.execute(sql)
                    conn.execute("INSERT INTO schema_migrations(version) VALUES (%s)", (version,))
        if readiness is not None:
            readiness.mark_ready()
    except Exception as exc:
        if readiness is not None:
            readiness.mark_failed(str(exc))
        raise MigrationError(f"failed to apply migrations from {path}") from exc


class SnapshotRepository:
    """JSONB snapshot helper with mandatory optimistic concurrency on updates."""

    def __init__(self, table: str) -> None:
        if not table.replace("_", "").isalnum():
            raise ValueError("snapshot table name must be alphanumeric/underscore")
        self._table = table

    def get(self, conn: Any, aggregate_id: str) -> tuple[int, dict[str, Any]] | None:
        row = conn.execute(f"SELECT version, data FROM {self._table} WHERE id = %s", (aggregate_id,)).fetchone()
        if row is None:
            return None
        return int(row[0]), dict(row[1])

    def save(self, conn: Any, aggregate_id: str, data: Mapping[str, Any], expected_version: int | None = None) -> int:
        payload = _jsonb_payload(dict(data))
        if expected_version is None:
            row = conn.execute(
                f"INSERT INTO {self._table}(id, version, data) VALUES (%s, 1, %s) "
                "ON CONFLICT (id) DO NOTHING RETURNING version",
                (aggregate_id, payload),
            ).fetchone()
            if row is None:
                raise OptimisticConcurrencyError(f"snapshot already exists for {aggregate_id}")
            return int(row[0])
        row = conn.execute(
            f"UPDATE {self._table} SET version = version + 1, data = %s, updated_at = now() "
            "WHERE id = %s AND version = %s RETURNING version",
            (payload, aggregate_id, expected_version),
        ).fetchone()
        if row is None:
            raise OptimisticConcurrencyError(f"concurrent update detected for {aggregate_id}")
        return int(row[0])


class OutboxAppender:
    def append(self, conn: Any, envelope: EventEnvelope, *, stream: str | None = None) -> None:
        envelope_json = envelope.to_json_dict()
        stream_name = stream or stream_for_producer(envelope.producer)
        payload = _jsonb_payload(envelope_json)
        conn.execute(
            "INSERT INTO outbox(event_id, stream, envelope) VALUES (%s, %s, %s) "
            "ON CONFLICT (event_id) DO NOTHING",
            (envelope.eventId, stream_name, payload),
        )


class OutboxRelay:
    """Background Redis relay for transactional outbox rows."""

    def __init__(self, pool: ConnectionPool, redis_client: Any | None = None, *, redis_url: str | None = None, poll_interval: float | None = None) -> None:
        self._pool = pool
        import os
        default_ms = int(os.environ.get("OUTBOX_POLL_INTERVAL_MS", "50"))
        self._poll_interval = min(poll_interval if poll_interval is not None else default_ms / 1000, 0.5)
        if redis_client is not None:
            self._redis = redis_client
        else:
            try:
                import redis
            except ImportError as exc:  # pragma: no cover
                raise StorageError("redis-py is not installed") from exc
            self._redis = redis.Redis.from_url(redis_url or os.getenv("REDIS_URL", "redis://localhost:6379"), decode_responses=True)
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> threading.Thread:
        if self._thread is not None and self._thread.is_alive():
            return self._thread
        self._stop.clear()
        self._thread = threading.Thread(target=self.run, name="outbox-relay", daemon=True)
        self._thread.start()
        return self._thread

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None and self._thread is not threading.current_thread():
            self._thread.join(timeout=5)

    _CLEANUP_EVERY = 20

    def run(self) -> None:
        poll_count = 0
        while not self._stop.is_set():
            try:
                self.relay_once()
                poll_count += 1
                if poll_count % self._CLEANUP_EVERY == 0:
                    self._cleanup()
            except Exception:
                time.sleep(self._poll_interval)
            self._stop.wait(self._poll_interval)

    def _cleanup(self) -> None:
        try:
            with self._pool.connection() as conn:
                conn.execute("DELETE FROM outbox WHERE published_at IS NOT NULL AND published_at < now() - interval '30 seconds'")
                conn.execute("DELETE FROM processed_events WHERE processed_at < now() - interval '5 minutes'")
                conn.execute("DELETE FROM idempotency_records WHERE created_at < now() - interval '10 minutes'")
                conn.commit()
        except Exception:
            pass

    def relay_once(self, *, limit: int = 100) -> int:
        published = 0
        with self._pool.connection() as conn:
            rows = conn.execute(
                "SELECT seq, stream, envelope FROM outbox WHERE published_at IS NULL ORDER BY seq LIMIT %s",
                (limit,),
            ).fetchall()
            for seq, stream, envelope in rows:
                payload = envelope if isinstance(envelope, str) else json.dumps(envelope, separators=(",", ":"))
                self._redis.xadd(stream, {"envelope": payload}, maxlen=MAXLEN, approximate=True)
                conn.execute("UPDATE outbox SET published_at = now() WHERE seq = %s AND published_at IS NULL", (seq,))
                published += 1
            conn.commit()
        return published


class ProcessedEventsGuard:
    def try_mark_processed(self, conn: Any, event_id: str, stream: str | None = None) -> bool:
        row = conn.execute(
            "INSERT INTO processed_events(event_id, stream) VALUES (%s, %s) "
            "ON CONFLICT DO NOTHING RETURNING event_id",
            (event_id, stream),
        ).fetchone()
        return row is not None

    @contextmanager
    def handling(self, conn: Any, event_id: str, stream: str | None = None) -> Iterator[bool]:
        with conn.transaction():
            inserted = self.try_mark_processed(conn, event_id, stream)
            yield inserted


class PostgresIdempotencyStore(IdempotencyStore):
    def __init__(self, pool: ConnectionPool) -> None:
        self._pool = pool

    @staticmethod
    def _compound_key(scope: str, key: str) -> str:
        return f"{scope}\u001f{key}"

    def get(self, scope: str, key: str) -> IdempotencyRecord | None:
        with self._pool.connection() as conn:
            row = conn.execute(
                "SELECT request_hash, status_code, response_body FROM idempotency_records WHERE key = %s",
                (self._compound_key(scope, key),),
            ).fetchone()
        if row is None:
            return None
        return IdempotencyRecord(fingerprint=str(row[0]), status_code=int(row[1]), response_body=row[2])

    def put(self, scope: str, key: str, record: IdempotencyRecord) -> None:
        with self._pool.connection() as conn:
            with conn.transaction():
                conn.execute(
                    "INSERT INTO idempotency_records(key, request_hash, status_code, response_body) VALUES (%s, %s, %s, %s) "
                    "ON CONFLICT (key) DO NOTHING",
                    (
                        self._compound_key(scope, key),
                        record.fingerprint,
                        record.status_code,
                        _jsonb_payload(record.response_body),
                    ),
                )


__all__ = [
    "DatabaseConfig",
    "DatabasePool",
    "MigrationError",
    "OptimisticConcurrencyError",
    "OutboxAppender",
    "OutboxRelay",
    "PostgresIdempotencyStore",
    "ProcessedEventsGuard",
    "ReadinessGate",
    "SnapshotRepository",
    "StorageError",
    "run_migrations",
]
