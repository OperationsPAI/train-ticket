package com.trainticket.platformkit.persistence;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class EventReplayArchiveController {
    private final EventReplayArchive archive;
    private final String producer;

    public EventReplayArchiveController(EventReplayArchive archive, String producer) {
        this.archive = Objects.requireNonNull(archive, "archive is required");
        this.producer = requireText(producer, "producer");
    }

    @GetMapping("/api/v1/internal/event-replay")
    public ResponseEntity<?> replay(@RequestParam(defaultValue = "0") long after,
                                    @RequestParam(defaultValue = "100") int limit) {
        try {
            EventReplayArchive.ReplayPage page = archive.readAfter(after, limit);
            return ResponseEntity.ok(Map.of(
                "producer", producer,
                "retentionPolicy", "EVENT_REPLAY_RETENTION_DAYS",
                "afterSequence", page.afterSequence(),
                "oldestAvailableSequence", page.oldestAvailableSequence() == null ? 0L : page.oldestAvailableSequence(),
                "latestAvailableSequence", page.latestAvailableSequence() == null ? 0L : page.latestAvailableSequence(),
                "retainedFrom", page.retainedFrom() == null ? "" : page.retainedFrom().toString(),
                "hasMore", page.hasMore(),
                "events", page.events()
            ));
        } catch (ReplayRetentionGapException gap) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "code", "REPLAY_RETENTION_GAP",
                "message", gap.getMessage(),
                "producer", producer,
                "requestedAfterSequence", gap.requestedAfter(),
                "oldestAvailableSequence", gap.oldestAvailable()
            ));
        }
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
