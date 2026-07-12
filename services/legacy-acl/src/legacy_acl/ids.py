from __future__ import annotations

from hashlib import sha256
from uuid import NAMESPACE_URL, UUID, uuid5

from train_ticket_platform.ids import new_uuid7


def prefixed_uuid7(prefix: str) -> str:
    return f"{prefix}-{new_uuid7()}"


def deterministic_event_id(source_ref: str, operation: str) -> str:
    digest = bytearray(sha256(f"legacy-acl:{operation}:{source_ref}".encode("utf-8")).digest()[:16])
    digest[6] = (digest[6] & 0x0F) | 0x70
    digest[8] = (digest[8] & 0x3F) | 0x80
    return f"evt-{UUID(bytes=bytes(digest))}"


def deterministic_prefixed_uuid(prefix: str, material: str) -> str:
    digest = bytearray(sha256(f"legacy-acl:{prefix}:{material}".encode("utf-8")).digest()[:16])
    digest[6] = (digest[6] & 0x0F) | 0x70
    digest[8] = (digest[8] & 0x3F) | 0x80
    return f"{prefix}-{UUID(bytes=bytes(digest))}"


def deterministic_uuid7(source_ref: str, suffix: str) -> str:
    seeded = uuid5(NAMESPACE_URL, f"train-ticket:legacy-acl:idempotency:{source_ref}:{suffix}")
    digest = bytearray(seeded.bytes)
    digest[6] = (digest[6] & 0x0F) | 0x70
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(UUID(bytes=bytes(digest)))
