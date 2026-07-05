from __future__ import annotations

from time import time
from secrets import randbits
from uuid import UUID


def new_uuid7() -> str:
    try:
        from uuid6 import uuid7

        return str(uuid7())
    except ImportError:  # pragma: no cover - package dependency supplies uuid6 in normal installs
        unix_ts_ms = int(time() * 1000) & ((1 << 48) - 1)
        uuid_int = unix_ts_ms << 80
        uuid_int |= 0x7 << 76
        uuid_int |= randbits(12) << 64
        uuid_int |= 0b10 << 62
        uuid_int |= randbits(62)
        return str(UUID(int=uuid_int))


def new_prefixed_uuid7(prefix: str) -> str:
    return f"{prefix}-{new_uuid7()}"


def is_uuid7(value: str) -> bool:
    try:
        parsed = UUID(value)
    except (TypeError, ValueError, AttributeError):
        return False
    return parsed.version == 7


def is_prefixed_uuid7(value: str, prefix: str) -> bool:
    expected = f"{prefix}-"
    return value.startswith(expected) and is_uuid7(value[len(expected) :])
