package com.trainticket.marketingcampaign.application;

import com.trainticket.marketingcampaign.domain.DomainEvent;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DomainEventPayloads {
    private DomainEventPayloads() {}

    public static Map<String, Object> toMap(DomainEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", event.getClass().getSimpleName());
        if (event.getClass().isRecord()) {
            for (RecordComponent component : event.getClass().getRecordComponents()) {
                try {
                    component.getAccessor().setAccessible(true);
                    payload.put(component.getName(), component.getAccessor().invoke(event));
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("domain event payload could not be read", exception);
                }
            }
        } else {
            payload.put("aggregateId", event.aggregateId());
            payload.put("occurredAt", event.occurredAt());
            payload.put("aggregateVersion", event.aggregateVersion());
        }
        return payload;
    }
}
