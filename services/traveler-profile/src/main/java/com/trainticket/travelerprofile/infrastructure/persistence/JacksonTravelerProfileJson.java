package com.trainticket.travelerprofile.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class JacksonTravelerProfileJson {
    private JacksonTravelerProfileJson() {
    }

    record TravelerProfileSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static TravelerProfileSnapshot of(ObjectNode data) {
            return new TravelerProfileSnapshot(data);
        }
    }

}
