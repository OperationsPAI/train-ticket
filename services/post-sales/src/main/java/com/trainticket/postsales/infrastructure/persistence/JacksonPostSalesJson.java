package com.trainticket.postsales.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class JacksonPostSalesJson {
    private JacksonPostSalesJson() {
    }

    record PostSalesCaseSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static PostSalesCaseSnapshot of(ObjectNode data) {
            return new PostSalesCaseSnapshot(data);
        }
    }

}
