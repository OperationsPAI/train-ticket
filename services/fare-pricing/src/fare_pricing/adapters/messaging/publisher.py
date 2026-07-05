from __future__ import annotations

import json
import os
import time
from typing import Any

from ...ports import EventEnvelope
from ...ports.messaging import EventPublisher, PublishFailed


class RedisEventPublisher(EventPublisher):
    """Redis Streams implementation of EventPublisher.

    Pushes events to stream "events:<producer>" using XADD.
    Only Redis types/imports live in this module (contract rule).
    """

    def __init__(self, redis_url: str | None = None) -> None:
        import redis as _redis

        self._redis_url = redis_url or os.environ.get("REDIS_URL", "redis://localhost:6379")
        self._client: _redis.Redis | None = None  # type: ignore[name-defined]

    def _get_client(self) -> Any:  # type: ignore[type-arg]
        import redis as _redis  # type: ignore[import-untyped]

        if self._client is None:
            self._client = _redis.from_url(self._redis_url, decode_responses=True)  # type: ignore[attr-defined]
        return self._client

    def publish(self, envelope: EventEnvelope) -> None:
        import redis as _redis  # type: ignore[import-untyped]

        stream_key = f"events:{envelope.producer}"
        payload_json = json.dumps(envelope.to_json_dict(), default=str)
        client = self._get_client()

        # Retry with exponential backoff (3 attempts)
        max_attempts = 3
        last_exc: Exception | None = None
        for attempt in range(max_attempts):
            try:
                client.xadd(stream_key, {"envelope": payload_json}, maxlen=100000, approximate=True)
                return
            except _redis.RedisError as exc:
                last_exc = exc
                if attempt < max_attempts - 1:
                    time.sleep(0.1 * (2 ** attempt))
        raise PublishFailed(f"Failed to publish event to {stream_key} after {max_attempts} attempts: {last_exc}") from last_exc
