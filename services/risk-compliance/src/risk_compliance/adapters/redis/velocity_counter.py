from __future__ import annotations

from datetime import UTC, datetime
from typing import Any
from uuid import uuid4
import os

from risk_compliance.domain import VelocityDimension


class VelocityRedisCounter:
    """Sliding-window velocity counter backed by Redis sorted sets."""

    def __init__(self, redis_client: Any | None = None, redis_url: str | None = None, key_prefix: str = "risk:velocity") -> None:
        if redis_client is not None:
            self._redis = redis_client
        else:
            import redis
            self._redis = redis.Redis.from_url(redis_url or os.getenv("REDIS_URL", "redis://localhost:6379"), decode_responses=True)
        self._key_prefix = key_prefix

    def increment_and_count(self, dimension: VelocityDimension, key: str, occurred_at: datetime, window_seconds: int) -> int:
        if not key.strip():
            return 0
        occurred_at = occurred_at.astimezone(UTC) if occurred_at.tzinfo else occurred_at.replace(tzinfo=UTC)
        now_ms = int(occurred_at.timestamp() * 1000)
        window_start_ms = now_ms - (window_seconds * 1000)
        redis_key = self._redis_key(dimension, key)
        member = f"{now_ms}:{uuid4()}"
        pipe = self._redis.pipeline()
        pipe.zadd(redis_key, {member: now_ms})
        pipe.zremrangebyscore(redis_key, 0, window_start_ms - 1)
        pipe.zcount(redis_key, window_start_ms, now_ms)
        pipe.expire(redis_key, max(window_seconds * 2, 60))
        results = pipe.execute()
        return int(results[2])

    def _redis_key(self, dimension: VelocityDimension, key: str) -> str:
        safe_key = key.replace(" ", "_")
        return f"{self._key_prefix}:{dimension.value}:{safe_key}"
