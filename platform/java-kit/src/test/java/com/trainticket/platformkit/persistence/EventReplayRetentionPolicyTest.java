package com.trainticket.platformkit.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EventReplayRetentionPolicyTest {
    @Test void defaultsToSevenDays() { assertEquals(Duration.ofDays(7), EventReplayRetentionPolicy.parse(null)); }
    @Test void acceptsExplicitDays() { assertEquals(Duration.ofDays(90), EventReplayRetentionPolicy.parse("90")); }
    @Test void rejectsUnboundedOrInvalidRetention() {
        assertThrows(IllegalArgumentException.class, () -> EventReplayRetentionPolicy.parse("0"));
        assertThrows(IllegalArgumentException.class, () -> EventReplayRetentionPolicy.parse("91"));
        assertThrows(IllegalArgumentException.class, () -> EventReplayRetentionPolicy.parse("forever"));
    }
}
