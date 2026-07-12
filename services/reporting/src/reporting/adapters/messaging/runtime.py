from __future__ import annotations

import os
import threading
from reporting.application.service import ReportingApplicationService
from reporting.ids import uuid7

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
        consumer_name = f"reporting-{os.getenv('HOSTNAME') or uuid7()}"
        # subscribe() runs the consume loop; it must not block FastAPI
        # lifespan startup, so it runs on a daemon thread.
        self._thread = threading.Thread(
            target=self.subscriber.subscribe,
            args=(
                reporting_subscription_streams(),
                CONSUMER_GROUP,
                consumer_name,
                self.service.handle_event,
            ),
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        self.subscriber.stop()
