from __future__ import annotations

import json
import time
from typing import Any

from reporting.application.ports import EventEnvelope, EventPublisher, PublishFailed

from .stream_config import STREAM_MAXLEN, stream_for_producer


class RedisEventPublisher(EventPublisher):
    def __init__(self, redis_url: str) -> None:
        try:
            from redis import Redis
        except ImportError as exc:  # pragma: no cover - depends on deployment deps
            raise PublishFailed("redis-py is required for RedisEventPublisher") from exc
        self._client = Redis.from_url(redis_url, decode_responses=True)

    def publish(self, envelope: EventEnvelope) -> None:
        stream = stream_for_producer(envelope.producer)
        body = json.dumps(envelope.as_dict(), separators=(",", ":"), sort_keys=False)
        delays = (0.05, 0.1, 0.2)
        last_error: Exception | None = None
        for attempt, delay in enumerate(delays, start=1):
            try:
                self._client.xadd(stream, {"envelope": body}, maxlen=STREAM_MAXLEN, approximate=True)
                return
            except Exception as exc:  # pragma: no cover - requires live Redis fault
                last_error = exc
                if attempt < len(delays):
                    time.sleep(delay)
        raise PublishFailed("failed to publish reporting event") from last_error
