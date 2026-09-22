from __future__ import annotations

import os
from typing import Any
import redis

from train_ticket_platform.messaging import RedisEventSubscriber as _RedisEventSubscriber


class RedisEventSubscriber(_RedisEventSubscriber):
    def __init__(self, redis_url: str | None = None, redis_client: Any | None = None) -> None:
        if redis_client is None:
            workers = int(os.environ.get("REPORTING_CONSUMERS_PER_STREAM", "4"))
            from .stream_config import reporting_subscription_streams
            connections = len(reporting_subscription_streams()) * workers + 8
            redis_client = redis.Redis.from_url(redis_url or os.getenv("REDIS_URL", "redis://localhost:6379"),
                decode_responses=True, max_connections=connections)
        super().__init__(redis_client=redis_client)

__all__ = ["RedisEventSubscriber"]
