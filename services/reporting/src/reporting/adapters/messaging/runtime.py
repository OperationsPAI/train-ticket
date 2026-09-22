from __future__ import annotations

import os
from reporting.application.service import ReportingApplicationService
from train_ticket_platform.messaging import default_consumer_name

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
        consumer_name = default_consumer_name(CONSUMER_GROUP)
        workers = int(os.environ.get("REPORTING_CONSUMERS_PER_STREAM", "4"))
        if workers < 1:
            raise ValueError("REPORTING_CONSUMERS_PER_STREAM must be positive")
        for stream in reporting_subscription_streams():
            for index in range(workers):
                self.subscriber.start_in_background(
                    (stream,), CONSUMER_GROUP, self.service.handle_event, consumer_name=f"{consumer_name}-{index}",
                )

    def stop(self) -> None:
        self.subscriber.stop()
