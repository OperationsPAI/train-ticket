from __future__ import annotations

import os
from uuid import uuid4

from reporting.application.service import ReportingApplicationService

from .publisher import RedisEventPublisher
from .stream_config import CONSUMER_GROUP, reporting_subscription_streams
from .subscriber import RedisEventSubscriber

DEFAULT_REDIS_URL = "redis://localhost:6379"


class MessagingRuntime:
    def __init__(self, service: ReportingApplicationService, redis_url: str | None = None) -> None:
        self.redis_url = redis_url or os.getenv("REDIS_URL", DEFAULT_REDIS_URL)
        self.publisher = RedisEventPublisher(self.redis_url)
        service.publisher = self.publisher
        self.subscriber = RedisEventSubscriber(self.redis_url)
        self.service = service

    def start(self) -> None:
        consumer_name = f"reporting-{os.getenv('HOSTNAME') or uuid4()}"
        self.subscriber.subscribe(
            reporting_subscription_streams(),
            CONSUMER_GROUP,
            consumer_name,
            self.service.handle_event,
        )

    def stop(self) -> None:
        self.subscriber.stop()
