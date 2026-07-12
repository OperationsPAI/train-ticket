from __future__ import annotations

import os
from typing import Any

from train_ticket_platform.messaging import RedisEventPublisher as _RedisEventPublisher


class RedisEventPublisher(_RedisEventPublisher):
    def __init__(self, redis_url: str | None = None, redis_client: Any | None = None) -> None:
        super().__init__(redis_client=redis_client, redis_url=redis_url or os.getenv("REDIS_URL", "redis://localhost:6379"))

__all__ = ["RedisEventPublisher"]
