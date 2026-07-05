from __future__ import annotations

import json
import logging
import time
from typing import Any

from trip_planning.events import EventEnvelope, EventPublisher, PublishFailed

logger = logging.getLogger(__name__)

STREAM_KEY_TEMPLATE = "events:{producer}"
MAXLEN = 100000
MAX_RETRIES = 3
RETRY_BACKOFF_SECONDS = [0.1, 0.3, 0.9]


class RedisEventPublisher(EventPublisher):
    """Redis Streams implementation of EventPublisher.

    Lives ONLY under adapters/messaging — no Redis types outside this module.
    """

    def __init__(self, redis_client: Any) -> None:
        """Initialize with a redis-py Redis client.

        Args:
            redis_client: A redis.Redis (or StrictRedis) instance.
        """
        self._redis = redis_client

    def publish(self, envelope: EventEnvelope) -> None:
        """Publish an event envelope to the Redis stream.

        The target stream is determined from the producer field:
        stream = events:<producer>

        Retries with exponential backoff on transient failures.
        """
        stream_key = STREAM_KEY_TEMPLATE.format(producer=envelope.producer)
        payload = json.dumps(envelope.to_json_dict(), ensure_ascii=False)

        last_error: Exception | None = None
        for attempt in range(MAX_RETRIES):
            try:
                self._redis.xadd(stream_key, {"envelope": payload}, maxlen=MAXLEN, approximate=True)
                return
            except Exception as exc:
                last_error = exc
                logger.warning(
                    "publish attempt %d/%d failed for event %s on stream %s: %s",
                    attempt + 1,
                    MAX_RETRIES,
                    envelope.eventId,
                    stream_key,
                    exc,
                )
                if attempt < MAX_RETRIES - 1:
                    time.sleep(RETRY_BACKOFF_SECONDS[attempt])

        raise PublishFailed(
            f"Failed to publish event {envelope.eventId} to {stream_key} "
            f"after {MAX_RETRIES} attempts: {last_error}"
        ) from last_error
