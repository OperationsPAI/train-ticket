package com.trainticket.bookingorchestration.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class JacksonBookingOrchestrationJson {
    private JacksonBookingOrchestrationJson() {
    }

    record BookingSagaSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static BookingSagaSnapshot of(ObjectNode data) {
            return new BookingSagaSnapshot(data);
        }
    }

    record SegmentBookingSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static SegmentBookingSnapshot of(ObjectNode data) {
            return new SegmentBookingSnapshot(data);
        }
    }

}
