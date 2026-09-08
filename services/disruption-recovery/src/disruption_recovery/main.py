from __future__ import annotations

import logging

from train_ticket_platform.trace_logging import install_trace_logging, trace_logging_log_format

from .api import create_app

# Installed before basicConfig, not left to init_opentelemetry: the format below
# names trace_id/span_id, and a record created before the factory exists would
# have neither attribute and make the handler raise instead of logging.
install_trace_logging()
logging.basicConfig(level=logging.INFO, format=trace_logging_log_format("service=disruption-recovery"))
app = create_app()
