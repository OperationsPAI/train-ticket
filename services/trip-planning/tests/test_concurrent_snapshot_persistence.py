import threading
import unittest

from train_ticket_platform.storage import OptimisticConcurrencyError

from trip_planning.adapters.storage.postgres import PostgresPlanStore


class _FakeConnection:
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def transaction(self):
        return self


class _FakePool:
    def connection(self):
        return _FakeConnection()


class _RacingSnapshotRepository:
    """Force two writers to read a missing snapshot before either insert."""

    def __init__(self) -> None:
        self._snapshot: tuple[int, dict[str, object]] | None = None
        self._lock = threading.Lock()
        self._first_read_barrier = threading.Barrier(2)
        self.conflicts = 0

    def get(self, conn: object, aggregate_id: str) -> tuple[int, dict[str, object]] | None:
        with self._lock:
            snapshot = self._snapshot
        if snapshot is None:
            self._first_read_barrier.wait(timeout=5)
        return None if snapshot is None else (snapshot[0], dict(snapshot[1]))

    def save(
        self,
        conn: object,
        aggregate_id: str,
        data: dict[str, object],
        expected_version: int | None = None,
    ) -> int:
        with self._lock:
            if expected_version is None:
                if self._snapshot is not None:
                    self.conflicts += 1
                    raise OptimisticConcurrencyError(f"snapshot already exists for {aggregate_id}")
                self._snapshot = (1, dict(data))
                return 1
            if self._snapshot is None or self._snapshot[0] != expected_version:
                self.conflicts += 1
                raise OptimisticConcurrencyError(f"concurrent update detected for {aggregate_id}")
            version = expected_version + 1
            self._snapshot = (version, dict(data))
            return version

    @property
    def snapshot(self) -> tuple[int, dict[str, object]] | None:
        with self._lock:
            return None if self._snapshot is None else (self._snapshot[0], dict(self._snapshot[1]))


class ConcurrentItinerarySnapshotPersistenceTest(unittest.TestCase):

    def test_previous_read_modify_write_pattern_raises_occ_on_concurrent_same_id_write(self) -> None:
        racing_repository = _RacingSnapshotRepository()
        itinerary = {"itineraryRef": "itin_4616aaaf830a1f7d", "legs": []}
        errors: list[BaseException] = []

        def naive_write() -> None:
            try:
                snap = racing_repository.get(object(), str(itinerary["itineraryRef"]))
                racing_repository.save(
                    object(),
                    str(itinerary["itineraryRef"]),
                    itinerary,
                    None if snap is None else int(snap[0]),
                )
            except BaseException as exc:  # captures the historical lost race
                errors.append(exc)

        threads = [threading.Thread(target=naive_write) for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=5)

        self.assertEqual(len(errors), 1)
        self.assertIsInstance(errors[0], OptimisticConcurrencyError)

    def test_concurrent_same_itinerary_snapshot_write_is_idempotent(self) -> None:
        store = PostgresPlanStore(_FakePool())
        racing_repository = _RacingSnapshotRepository()
        store._itineraries = racing_repository  # type: ignore[attr-defined]
        itinerary = {
            "itineraryRef": "itin_4616aaaf830a1f7d",
            "legs": [],
            "priceHint": {"currency": "CNY", "minorUnits": 0},
            "availabilityHint": {"status": "UNKNOWN", "confidence": 50},
        }
        errors: list[BaseException] = []

        def write_itinerary() -> None:
            try:
                store.save_itinerary(itinerary)
            except BaseException as exc:  # pragma: no cover - asserted below for thread failures
                errors.append(exc)

        threads = [threading.Thread(target=write_itinerary) for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=5)

        self.assertEqual(errors, [])
        self.assertEqual(racing_repository.conflicts, 1)
        self.assertEqual(racing_repository.snapshot, (1, itinerary))
