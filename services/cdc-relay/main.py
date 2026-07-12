"""CDC Outbox Relay — event-driven via PG LISTEN/NOTIFY.

Uses PG native LISTEN/NOTIFY for instant event delivery (sub-ms latency)
instead of polling or WAL-based logical replication. No wal_level=logical
required, no replication slots, no disk space issues.

Architecture:
  1. SQL trigger on outbox INSERT → pg_notify('outbox_new', seq::text)
  2. This relay: LISTEN outbox_new → SELECT → XADD Redis → UPDATE published_at
  3. Fallback: 500ms poll catches anything missed by NOTIFY
"""

import asyncio
import json
import os
import signal
import sys
import time
from dataclasses import dataclass

import psycopg
from psycopg import sql
import redis.asyncio as aioredis


@dataclass
class PgSource:
    name: str
    host: str
    port: int
    databases: list[str]


def parse_pg_instances(config: str) -> list[PgSource]:
    sources = []
    for part in config.split(";"):
        fields = part.strip().split(":")
        if len(fields) >= 4:
            name, host, port, dbs = fields[0], fields[1], int(fields[2]), fields[3].split(",")
            sources.append(PgSource(name=name, host=host, port=port, databases=dbs))
    return sources


TRIGGER_SQL = """
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'outbox_notify') THEN
        CREATE FUNCTION outbox_notify() RETURNS trigger AS $fn$
        BEGIN
            PERFORM pg_notify('outbox_new', NEW.seq::text);
            RETURN NEW;
        END;
        $fn$ LANGUAGE plpgsql;
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'outbox_after_insert') THEN
        CREATE TRIGGER outbox_after_insert
            AFTER INSERT ON outbox
            FOR EACH ROW EXECUTE FUNCTION outbox_notify();
    END IF;
END $$;
"""


async def setup_trigger(conninfo: str, db: str) -> bool:
    """Install the NOTIFY trigger on the outbox table."""
    try:
        async with await psycopg.AsyncConnection.connect(
            f"{conninfo} dbname={db}", autocommit=True
        ) as conn:
            await conn.execute("SELECT 1 FROM outbox LIMIT 0")
            await conn.execute(TRIGGER_SQL)
            return True
    except Exception as e:
        if "does not exist" in str(e):
            return False  # no outbox table in this DB
        print(f"[{db}] trigger setup warning: {e}", flush=True)
        return False


async def cdc_worker(source: PgSource, db: str, redis_client: aioredis.Redis, pg_user: str, pg_password: str):
    """Event-driven outbox relay using LISTEN/NOTIFY + fallback poll."""
    conninfo = f"host={source.host} port={source.port} user={pg_user} password={pg_password}"

    has_outbox = await setup_trigger(conninfo, db)
    if not has_outbox:
        return  # silently skip DBs without outbox

    print(f"[{db}] CDC worker ready (LISTEN/NOTIFY + 500ms fallback)", flush=True)
    published = 0

    while True:
        try:
            async with await psycopg.AsyncConnection.connect(
                f"{conninfo} dbname={db}", autocommit=True
            ) as conn:
                await conn.execute("LISTEN outbox_new")

                while True:
                    # Process any pending outbox rows
                    count = await relay_batch(conn, redis_client, db)
                    published += count
                    if count > 0 and published % 500 == 0:
                        print(f"[{db}] published {published} events", flush=True)

                    # Wait for NOTIFY or fallback timeout (500ms)
                    try:
                        async for notify in conn.notifies(timeout=0.5):
                            # Got notification — break to process immediately
                            break
                    except TimeoutError:
                        pass  # fallback poll

        except Exception as e:
            print(f"[{db}] error: {e}, reconnecting in 1s", flush=True)
            await asyncio.sleep(1)


async def relay_batch(conn, redis_client: aioredis.Redis, db: str) -> int:
    """Relay unpublished outbox rows to Redis. Returns count published."""
    result = await conn.execute(
        "SELECT seq, stream, envelope FROM outbox WHERE published_at IS NULL ORDER BY seq LIMIT 200"
    )
    rows = await result.fetchall()
    if not rows:
        return 0

    pipe = redis_client.pipeline()
    seqs = []
    for row in rows:
        seq, stream, envelope = row[0], row[1], row[2]
        envelope_str = json.dumps(envelope) if isinstance(envelope, dict) else str(envelope)
        pipe.xadd(stream, {"envelope": envelope_str}, maxlen=100000, approximate=True)
        seqs.append(seq)
    await pipe.execute()

    placeholders = ",".join(str(s) for s in seqs)
    await conn.execute(f"UPDATE outbox SET published_at = now() WHERE seq IN ({placeholders})")
    return len(seqs)


async def main():
    redis_url = os.environ.get("REDIS_URL", "redis://localhost:6379")
    pg_instances_config = os.environ.get("PG_INSTANCES", "")
    pg_user = os.environ.get("PG_USER", "trainticket")
    pg_password = os.environ.get("PG_PASSWORD", "trainticket-dev")

    if not pg_instances_config:
        print("PG_INSTANCES env required", flush=True)
        sys.exit(1)

    sources = parse_pg_instances(pg_instances_config)
    redis_client = aioredis.from_url(redis_url, decode_responses=True)

    print(f"CDC relay starting (LISTEN/NOTIFY mode): {sum(len(s.databases) for s in sources)} databases", flush=True)

    tasks = []
    for source in sources:
        for db in source.databases:
            tasks.append(asyncio.create_task(cdc_worker(source, db, redis_client, pg_user, pg_password)))

    loop = asyncio.get_event_loop()
    stop = asyncio.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop.set)

    await stop.wait()
    for t in tasks:
        t.cancel()
    await redis_client.aclose()
    print("CDC relay stopped", flush=True)


if __name__ == "__main__":
    asyncio.run(main())
