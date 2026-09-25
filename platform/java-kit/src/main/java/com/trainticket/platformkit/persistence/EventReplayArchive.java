package com.trainticket.platformkit.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Durable, bounded source for rebuilding consumers after Redis stream loss.
 *
 * <p>The normal outbox remains a short-lived delivery queue. This archive is a
 * separate system-of-record copy with an explicit retention deadline and a
 * monotonically increasing source cursor. Reading the archive has no business
 * side effects; consumers still apply their own durable event-id deduplication.</p>
 */
public final class EventReplayArchive {
    public static final int MAX_PAGE_SIZE = 500;
    private final JdbcOperations jdbc;

    public EventReplayArchive(javax.sql.DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public EventReplayArchive(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    public ReplayPage readAfter(long afterSequence, int requestedLimit) {
        if (afterSequence < 0) throw new IllegalArgumentException("after must not be negative");
        int limit = Math.max(1, Math.min(requestedLimit, MAX_PAGE_SIZE));
        ArchiveBounds bounds = jdbc.query(
            "SELECT min(archive_seq) FILTER (WHERE retained_until>now()), "
                + "max(archive_seq) FILTER (WHERE retained_until>now()), "
                + "min(archived_at) FILTER (WHERE retained_until>now()), "
                + "greatest(coalesce(max(archive_seq) FILTER (WHERE retained_until<=now()),0), "
                + "coalesce((SELECT discarded_through_seq FROM event_replay_retention_state WHERE singleton=true),0)) "
                + "FROM event_replay_archive",
            rs -> rs.next() ? new ArchiveBounds(nullableLong(rs, 1), nullableLong(rs, 2),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), nullableLong(rs, 4))
                : new ArchiveBounds(null, null, null, null)
        );
        if (bounds != null && bounds.discardedThroughSequence() != null
                && afterSequence < bounds.discardedThroughSequence()) {
            throw new ReplayRetentionGapException(afterSequence, bounds.discardedThroughSequence() + 1);
        }
        List<ArchivedEvent> rows = jdbc.query(
            "SELECT archive_seq,event_id,stream,envelope::text,archived_at,retained_until "
                + "FROM event_replay_archive WHERE archive_seq>? AND retained_until>now() "
                + "ORDER BY archive_seq LIMIT ?",
            (rs, row) -> new ArchivedEvent(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant()),
            afterSequence, limit + 1
        );
        boolean hasMore = rows.size() > limit;
        List<ArchivedEvent> page = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        return new ReplayPage(afterSequence, bounds == null ? null : bounds.oldestSequence(),
            bounds == null ? null : bounds.latestSequence(), bounds == null ? null : bounds.retainedFrom(),
            hasMore, page);
    }

    public int deleteExpired(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        Integer removed = jdbc.queryForObject("WITH expired AS (SELECT ctid,archive_seq FROM event_replay_archive "
            + "WHERE retained_until<=now() ORDER BY archive_seq LIMIT ? FOR UPDATE SKIP LOCKED), "
            + "deleted AS (DELETE FROM event_replay_archive WHERE ctid IN (SELECT ctid FROM expired) RETURNING archive_seq), "
            + "watermark AS (INSERT INTO event_replay_retention_state(singleton,discarded_through_seq,updated_at) "
            + "SELECT true,max(archive_seq),now() FROM deleted HAVING count(*)>0 "
            + "ON CONFLICT(singleton) DO UPDATE SET discarded_through_seq=greatest(event_replay_retention_state.discarded_through_seq,EXCLUDED.discarded_through_seq),updated_at=now()) "
            + "SELECT count(*) FROM deleted", Integer.class, limit);
        return removed == null ? 0 : removed;
    }

    private static Long nullableLong(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record ArchivedEvent(long sequence, String eventId, String stream, String envelope,
                                Instant archivedAt, Instant retainedUntil) {}
    public record ReplayPage(long afterSequence, Long oldestAvailableSequence, Long latestAvailableSequence,
                             Instant retainedFrom, boolean hasMore, List<ArchivedEvent> events) {}
    record ArchiveBounds(Long oldestSequence, Long latestSequence, Instant retainedFrom, Long discardedThroughSequence) {}
}
