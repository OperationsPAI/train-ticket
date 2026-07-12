from __future__ import annotations

import logging

from .api import create_app

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s service=transfer-management %(name)s %(message)s")
app = create_app()
