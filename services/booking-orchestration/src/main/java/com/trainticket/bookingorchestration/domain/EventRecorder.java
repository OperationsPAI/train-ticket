package com.trainticket.bookingorchestration.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class EventRecorder {
    private final Clock clock;
    private final List<DomainEvent> events = new ArrayList<>();

    EventRecorder(Clock clock) {
        this.clock = clock;
    }

    Instant now() {
        return clock.instant();
    }

    String nextEventId() {
        return UUID.randomUUID().toString();
    }

    void record(DomainEvent event) {
        events.add(event);
    }

    List<DomainEvent> pullEvents() {
        List<DomainEvent> copy = List.copyOf(events);
        events.clear();
        return copy;
    }

    List<DomainEvent> peekEvents() {
        return List.copyOf(events);
    }
}
