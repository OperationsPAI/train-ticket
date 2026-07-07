package com.trainticket.financesettlement.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class JacksonFinanceSettlementJson {
    private JacksonFinanceSettlementJson() {
    }

    record RevenueRecognitionSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static RevenueRecognitionSnapshot of(ObjectNode data) {
            return new RevenueRecognitionSnapshot(data);
        }
    }

    record ReconciliationCaseSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static ReconciliationCaseSnapshot of(ObjectNode data) {
            return new ReconciliationCaseSnapshot(data);
        }
    }

    record InvoiceSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static InvoiceSnapshot of(ObjectNode data) {
            return new InvoiceSnapshot(data);
        }
    }

}
