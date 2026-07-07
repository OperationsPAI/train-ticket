package com.trainticket.journeyorder.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class JacksonJourneyOrderJson {
    private JacksonJourneyOrderJson() {
    }

    record JourneyOrderSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static JourneyOrderSnapshot of(ObjectNode data) {
            return new JourneyOrderSnapshot(data);
        }
    }

    record AccountOrderStateSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static AccountOrderStateSnapshot of(ObjectNode data) {
            return new AccountOrderStateSnapshot(data);
        }
    }

}
