-- Index the reserved pre-order-check lookup that runs on every JourneyOrderCreated.
--
-- find_reserved_pre_order_check_for_order_event filters on three JSON fields --
-- data->>'status', data->'body'->>'result' and data->'body'->>'accountId' --
-- while the table's only indexes cover id, materialHash and orderIntentId. None
-- of the three is usable, so the query plans a Parallel Seq Scan of the whole
-- table.
--
-- Measured on the live cluster with 2,032,031 rows / 3856 MB: the plan read
-- 407,848 buffers and removed 674,931 rows per worker to return 0 rows, and
-- pg_stat_user_tables reported 1,053,815 sequential scans having read 283
-- billion tuples, growing at 237 million tuples per minute. postgres-core held
-- 16.2 CPU cores doing it, and identity-verification's p99 was 4.5 seconds.
--
-- The scan also caused a second failure. dynamic_shared_memory_type is posix,
-- so each of the two parallel workers allocated a DSM segment from /dev/shm,
-- and at this call rate they exhausted it. Parallel queries then raised
-- DiskFull rather than falling back to a serial plan; the container's /dev/shm
-- is now sized in deploy/helm/train-ticket/values.yaml, and this index removes
-- the demand that filled it.
--
-- accountId leads the index: it is the selective column, with 939,801 distinct
-- values over 2,032,031 rows and 2.16 rows per account on average. status and
-- result follow to keep the lookup index-only on the filter, though neither is
-- selective on its own -- status is 75% RESERVED and result is 99.9% PASS.
-- evaluatedAt is not indexed: the ORDER BY sorts the handful of rows one
-- account has, which a sort handles for free.
--
-- The remaining travelerRefs and segmentRefs conditions stay in Python. They
-- are set equality rather than containment, so they cannot be expressed as an
-- index predicate, and after this index they filter single-digit row counts
-- rather than the whole table.
CREATE INDEX IF NOT EXISTS idx_pre_order_check_account_status
    ON pre_order_check_snapshots (
        ((data->'body'->>'accountId')),
        ((data->>'status')),
        ((data->'body'->>'result'))
    );
