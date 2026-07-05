from __future__ import annotations

from datetime import UTC, datetime
from uuid import UUID
import random


def uuid7() -> str:
    """Return a UUID v7 string for all locally generated identifiers."""
    try:
        from uuid6 import uuid7 as uuid6_uuid7

        return str(uuid6_uuid7())
    except ImportError:  # pragma: no cover - uuid6 is a runtime dependency; fallback is defensive
        unix_ms = int(datetime.now(UTC).timestamp() * 1000)
        random_bits = random.getrandbits(74)
        value = (unix_ms & ((1 << 48) - 1)) << 80
        value |= 0x7 << 76
        value |= ((random_bits >> 62) & 0xFFF) << 64
        value |= 0b10 << 62
        value |= random_bits & ((1 << 62) - 1)
        return str(UUID(int=value))


def prefixed_uuid7(prefix: str) -> str:
    return f"{prefix}-{uuid7()}"


def is_uuid7(value: str) -> bool:
    try:
        parsed = UUID(value)
    except (ValueError, AttributeError, TypeError):
        return False
    return parsed.version == 7
