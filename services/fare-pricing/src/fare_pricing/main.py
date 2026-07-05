from __future__ import annotations

from .api import create_app
from .runtime_messaging import configure_event_subscriber

app = create_app()
configure_event_subscriber(app)
