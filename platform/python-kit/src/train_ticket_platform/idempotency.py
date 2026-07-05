from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass
from hashlib import sha256
import json
from typing import Any, Protocol

from .ids import is_uuid7


class IdempotencyKeyError(ValueError):
    pass


class IdempotencyKeyReusedError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class IdempotencyRecord:
    fingerprint: str
    status_code: int
    response_body: dict[str, Any]
    headers: dict[str, str] | None = None

    @property
    def request_hash(self) -> str:
        return self.fingerprint

    @property
    def body(self) -> dict[str, Any]:
        return self.response_body


class IdempotencyStore(Protocol):
    def get(self, scope: str, key: str) -> IdempotencyRecord | None: ...

    def put(self, scope: str, key: str, record: IdempotencyRecord) -> None: ...


class BoundedInMemoryIdempotencyStore(IdempotencyStore):
    def __init__(self, max_entries: int = 1024) -> None:
        self._max_entries = max_entries
        self._records: OrderedDict[tuple[str, str], IdempotencyRecord] = OrderedDict()

    def get(self, scope: str, key: str) -> IdempotencyRecord | None:
        compound_key = (scope, key)
        record = self._records.get(compound_key)
        if record is not None:
            self._records.move_to_end(compound_key)
        return record

    def put(self, scope: str, key: str, record: IdempotencyRecord) -> None:
        compound_key = (scope, key)
        self._records[compound_key] = record
        self._records.move_to_end(compound_key)
        while len(self._records) > self._max_entries:
            self._records.popitem(last=False)


def require_uuid7_idempotency_key(key: str | None) -> str:
    if not key:
        raise IdempotencyKeyError("Idempotency-Key header is required")
    if not is_uuid7(key):
        raise IdempotencyKeyError("Idempotency-Key must be a UUID v7")
    return key


def request_fingerprint(body: Any) -> str:
    canonical = json.dumps(body, sort_keys=True, separators=(",", ":"), default=str)
    return sha256(canonical.encode("utf-8")).hexdigest()
