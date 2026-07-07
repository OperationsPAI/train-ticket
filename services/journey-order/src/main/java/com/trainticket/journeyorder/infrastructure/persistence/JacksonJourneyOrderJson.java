package com.trainticket.journeyorder.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trainticket.journeyorder.domain.AccountOrderState;
import com.trainticket.journeyorder.domain.ConfirmationConditions;
import com.trainticket.journeyorder.domain.JourneyOrder;
import com.trainticket.journeyorder.domain.OfferSnapshotRef;
import com.trainticket.journeyorder.domain.OrderItem;
import com.trainticket.journeyorder.domain.OrderLifecycleState;
import com.trainticket.journeyorder.domain.SegmentOrderSnapshot;
import com.trainticket.journeyorder.domain.TimelineFact;
import com.trainticket.journeyorder.domain.TravelerRef;
import java.util.List;

final class JacksonJourneyOrderJson {
    private JacksonJourneyOrderJson() {}

    record JourneyOrderSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING) static JourneyOrderSnapshot of(ObjectNode data) { return new JourneyOrderSnapshot(data); }
    }

    record AccountOrderStateSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING) static AccountOrderStateSnapshot of(ObjectNode data) { return new AccountOrderStateSnapshot(data); }
    }

    static JourneyOrderSnapshot orderSnapshot(JourneyOrder order, String idempotencyKey, ObjectMapper mapper) {
        ObjectNode node = mapper.valueToTree(order);
        node.put("orderId", order.orderId());
        node.put("accountId", order.accountId());
        node.put("status", com.trainticket.journeyorder.application.service.OrderManagementService.toApiStatus(order));
        node.put("state", order.state().name());
        node.put("idempotencyKey", idempotencyKey);
        node.put("createdAt", order.timeline().isEmpty() ? null : order.timeline().getFirst().occurredAt().toString());
        return new JourneyOrderSnapshot(node);
    }

    static JourneyOrder toOrder(JourneyOrderSnapshot snapshot, ObjectMapper mapper) {
        ObjectNode n = snapshot.data();
        return JourneyOrder.rehydrate(
            n.path("orderId").asText(),
            n.path("accountId").asText(),
            n.path("channelRef").asText(),
            n.path("idempotencyKey").asText(),
            mapper.convertValue(n.get("offerSnapshot"), OfferSnapshotRef.class),
            mapper.convertValue(n.get("travelers"), new TypeReference<List<TravelerRef>>() {}),
            mapper.convertValue(n.get("segments"), new TypeReference<List<SegmentOrderSnapshot>>() {}),
            mapper.convertValue(n.get("orderItems"), new TypeReference<List<OrderItem>>() {}),
            OrderLifecycleState.valueOf(n.path("state").asText()),
            mapper.convertValue(n.get("confirmationConditions"), ConfirmationConditions.class),
            mapper.convertValue(n.get("timeline"), new TypeReference<List<TimelineFact>>() {})
        );
    }

    static AccountOrderStateSnapshot accountStateSnapshot(String accountId, AccountOrderState state, ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("accountId", accountId);
        node.put("state", state.name());
        return new AccountOrderStateSnapshot(node);
    }
}
