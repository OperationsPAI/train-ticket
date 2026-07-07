package com.trainticket.adminaudit.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class JacksonAdminAuditJson {
    private JacksonAdminAuditJson() {
    }

    record OperatorIdentitySnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static OperatorIdentitySnapshot of(ObjectNode data) {
            return new OperatorIdentitySnapshot(data);
        }
    }

    record ManualActionSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static ManualActionSnapshot of(ObjectNode data) {
            return new ManualActionSnapshot(data);
        }
    }

    record AuditEntrySnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static AuditEntrySnapshot of(ObjectNode data) {
            return new AuditEntrySnapshot(data);
        }
    }

}
