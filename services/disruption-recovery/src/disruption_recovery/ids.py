from __future__ import annotations

from train_ticket_platform.ids import new_prefixed_uuid7, new_uuid7


def uuid7() -> str:
    return new_uuid7()


def prefixed_uuid7(prefix: str) -> str:
    return new_prefixed_uuid7(prefix)
