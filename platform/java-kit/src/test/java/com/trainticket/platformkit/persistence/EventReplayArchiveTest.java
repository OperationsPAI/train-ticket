package com.trainticket.platformkit.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class EventReplayArchiveTest {
    private static final EventEnvelope EVENT = new EventEnvelope(
        "evt-0199a000-0000-7000-8000-000000000001", "BenefitIssued",
        Instant.parse("2026-09-25T04:00:00Z"),
        "corr-0199a000-0000-7000-8000-000000000003",
        "cmd-0199a000-0000-7000-8000-000000000002", "wallet-promotion", 1,
        Map.of("caseId", "psc-1"), null, null);

    @Test
    void archivingAppenderWritesDeliveryAndReplayCopies() throws Exception {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        new OutboxAppender(jdbc, new ObjectMapper().findAndRegisterModules(), Duration.ofDays(30), java.util.Set.of("BenefitIssued")).append(EVENT);

        verify(jdbc).update(
            org.mockito.ArgumentMatchers.startsWith("INSERT INTO outbox"),
            org.mockito.ArgumentMatchers.eq(EVENT.eventId()),
            org.mockito.ArgumentMatchers.eq("events:wallet-promotion"),
            anyString());
        verify(jdbc).update(
            org.mockito.ArgumentMatchers.startsWith("INSERT INTO event_replay_archive"),
            org.mockito.ArgumentMatchers.eq(EVENT.eventId()),
            org.mockito.ArgumentMatchers.eq(EVENT.eventType()),
            org.mockito.ArgumentMatchers.eq("events:wallet-promotion"),
            anyString(),
            org.mockito.ArgumentMatchers.eq(Duration.ofDays(30).toMillis()));
    }

    @Test
    void conflictingReplayIdentityIsRejected() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class), any(Object[].class))).thenReturn(0);
        OutboxAppender appender = new OutboxAppender(jdbc, new ObjectMapper().findAndRegisterModules(), Duration.ofDays(30), java.util.Set.of("BenefitIssued"));
        assertThrows(IllegalStateException.class, () -> appender.append(EVENT));
    }

    @Test
    void unrelatedEventsRemainInTheShortLivedOutboxOnly() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        EventEnvelope unrelated = new EventEnvelope(
            EVENT.eventId(), "BenefitRedeemed", EVENT.occurredAt(), EVENT.correlationId(),
            EVENT.causationId(), EVENT.producer(), EVENT.schemaVersion(), EVENT.payload(), null, null);
        new OutboxAppender(jdbc, new ObjectMapper().findAndRegisterModules(), Duration.ofDays(7), java.util.Set.of("BenefitIssued"))
            .append(unrelated);
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO event_replay_archive"), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void replayPageIsOrderedBoundedAndSignalsMore() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenReturn(new EventReplayArchive.ArchiveBounds(3L, 9L, Instant.parse("2026-09-25T04:00:00Z"), 2L));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(
            archived(4), archived(5), archived(6)));
        EventReplayArchive.ReplayPage page = new EventReplayArchive(jdbc).readAfter(3, 2);
        assertEquals(List.of(4L, 5L), page.events().stream().map(EventReplayArchive.ArchivedEvent::sequence).toList());
        assertEquals(true, page.hasMore());
        assertEquals(3L, page.oldestAvailableSequence());
        assertEquals(9L, page.latestAvailableSequence());
    }

    @SuppressWarnings("unchecked")
    @Test
    void expiredCursorFailsClosedInsteadOfSkippingHistory() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenReturn(new EventReplayArchive.ArchiveBounds(20L, 25L, Instant.parse("2026-09-25T04:00:00Z"), 19L));
        assertThrows(ReplayRetentionGapException.class, () -> new EventReplayArchive(jdbc).readAfter(10, 100));
    }

    @SuppressWarnings("unchecked")
    @Test
    void sequenceHolesFromRolledBackTransactionsAreNotRetentionGaps() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenReturn(new EventReplayArchive.ArchiveBounds(20L, 25L, Instant.parse("2026-09-25T04:00:00Z"), null));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        assertEquals(0, new EventReplayArchive(jdbc).readAfter(10, 100).events().size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void fullyExpiredArchiveStillSignalsAReplayGap() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenReturn(new EventReplayArchive.ArchiveBounds(null, null, null, 25L));
        assertThrows(ReplayRetentionGapException.class, () -> new EventReplayArchive(jdbc).readAfter(0, 100));
    }

    @SuppressWarnings("unchecked")
    @Test
    void logicallyExpiredRowsSignalGapBeforeTheSweeperDeletesThem() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenReturn(new EventReplayArchive.ArchiveBounds(12L, 14L, Instant.parse("2026-09-25T04:00:00Z"), 11L));
        assertThrows(ReplayRetentionGapException.class, () -> new EventReplayArchive(jdbc).readAfter(10, 100));
    }

    private static EventReplayArchive.ArchivedEvent archived(long sequence) {
        return new EventReplayArchive.ArchivedEvent(sequence, "evt-" + sequence, "events:payment", "{}",
            Instant.parse("2026-09-25T04:00:00Z"), Instant.parse("2026-10-25T04:00:00Z"));
    }
}
