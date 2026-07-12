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
        // JourneyOrder and OrderItem are behavior-rich classes without Jackson-visible
        // properties, so the snapshot is assembled field by field (mirror of toOrder).
        ObjectNode node = mapper.createObjectNode();
        node.put("orderId", order.orderId());
        node.put("accountId", order.accountId());
        node.put("channelRef", order.channelRef());
        node.put("status", com.trainticket.journeyorder.application.service.OrderManagementService.toApiStatus(order));
        node.put("state", order.state().name());
        node.put("idempotencyKey", idempotencyKey);
        node.put("createdAt", order.timeline().isEmpty() ? null : order.timeline().getFirst().occurredAt().toString());
        node.set("offerSnapshot", mapper.valueToTree(order.offerSnapshot()));
        node.set("travelers", mapper.valueToTree(order.travelers()));
        node.set("segments", mapper.valueToTree(order.segments()));
        node.set("orderItems", orderItemsNode(order.orderItems(), mapper));
        node.set("confirmationConditions", mapper.valueToTree(order.confirmationConditions()));
        node.set("timeline", mapper.valueToTree(order.timeline()));
        return new JourneyOrderSnapshot(node);
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode orderItemsNode(List<OrderItem> items, ObjectMapper mapper) {
        com.fasterxml.jackson.databind.node.ArrayNode array = mapper.createArrayNode();
        for (OrderItem item : items) {
            ObjectNode n = array.addObject();
            n.put("orderItemId", item.orderItemId());
            n.put("type", item.type().name());
            n.put("description", item.description());
            ObjectNode amount = n.putObject("amount");
            amount.put("currency", item.amount().currency().getCurrencyCode());
            amount.put("amount", item.amount().amount().toPlainString());
            n.put("commercialReasonRef", item.commercialReasonRef());
            n.set("bindings", mapper.valueToTree(item.bindings()));
            n.put("cancelled", item.cancelled());
            n.put("cancellationReason", item.cancellationReason());
        }
        return array;
    }

    private static List<OrderItem> toOrderItems(com.fasterxml.jackson.databind.JsonNode array, ObjectMapper mapper) {
        List<OrderItem> items = new java.util.ArrayList<>();
        if (array == null || array.isNull()) {
            return items;
        }
        for (com.fasterxml.jackson.databind.JsonNode n : array) {
            OrderItem item = new OrderItem(
                n.path("orderItemId").asText(),
                com.trainticket.journeyorder.domain.OrderItemType.valueOf(n.path("type").asText()),
                n.path("description").asText(),
                com.trainticket.journeyorder.domain.Money.of(
                    n.path("amount").path("currency").asText(),
                    n.path("amount").path("amount").asText()
                ),
                n.path("commercialReasonRef").asText(),
                mapper.convertValue(n.get("bindings"),
                    new TypeReference<List<com.trainticket.journeyorder.domain.OrderLineBinding>>() {})
            );
            if (n.path("cancelled").asBoolean(false)) {
                item.cancel(n.path("cancellationReason").asText("cancelled"));
            }
            items.add(item);
        }
        return items;
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
            toOrderItems(n.get("orderItems"), mapper),
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
