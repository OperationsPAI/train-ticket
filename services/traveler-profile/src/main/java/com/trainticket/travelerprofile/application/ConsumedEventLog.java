package com.trainticket.travelerprofile.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ConsumedEventLog {
    private final Set<String> consumedEventIds = ConcurrentHashMap.newKeySet();
    private final Clock clock;

    public ConsumedEventLog() {
        this(Clock.systemUTC());
    }

    ConsumedEventLog(Clock clock) {
        this.clock = clock;
    }

    public void record(String eventId) {
        consumedEventIds.add(eventId);
    }

    public boolean hasConsumed(String eventId) {
        return consumedEventIds.contains(eventId);
    }

    public Instant now() {
        return clock.instant();
    }
}
