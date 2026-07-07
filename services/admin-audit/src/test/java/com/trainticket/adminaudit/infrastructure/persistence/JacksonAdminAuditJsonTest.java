package com.trainticket.adminaudit.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class JacksonAdminAuditJsonTest {
    @Test
    void snapshotSerializesBusinessFieldsAtTopLevel() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode data = objectMapper.createObjectNode();
        data.put("businessField", "value");

        String json = objectMapper.writeValueAsString(new JacksonAdminAuditJson.OperatorIdentitySnapshot(data));
        JsonNode root = objectMapper.readTree(json);

        assertEquals("value", root.path("businessField").asText());
        assertTrue(root.has("businessField"));
        assertFalse(root.has("data"));
    }
}
