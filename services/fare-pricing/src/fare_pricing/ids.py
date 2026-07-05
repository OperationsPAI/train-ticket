from __future__ import annotations

from uuid6 import uuid7 as _uuid7


def uuid7() -> str:
    """Return a canonical UUID version 7 string."""
    return str(_uuid7())


def prefixed_uuid7(prefix: str) -> str:
    """Return a contract identifier using the given prefix and a UUID v7."""
    return f"{prefix}-{uuid7()}"
