package com.trainticket.platformkit.messaging;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryEventBus {
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Queue<Entry>> streams = new HashMap<>();
    private final Map<String, List<Entry>> dlqs = new HashMap<>();
    private final Map<GroupKey, Map<String, Pending>> pending = new HashMap<>();
    private final Map<GroupKey, Set<String>> consumedEventIds = new HashMap<>();

    public synchronized String publish(String stream, EventEnvelope envelope) {
        String id = sequence.incrementAndGet() + "-0";
        streams.computeIfAbsent(stream, ignored -> new ArrayDeque<>()).add(new Entry(id, envelope));
        return id;
    }

    synchronized List<Delivery> read(String stream, String group, int count) {
        GroupKey key = new GroupKey(stream, group);
        Queue<Entry> entries = streams.computeIfAbsent(stream, ignored -> new ArrayDeque<>());
        List<Delivery> deliveries = new ArrayList<>();
        while (!entries.isEmpty() && deliveries.size() < count) {
            Entry entry = entries.poll();
            Pending pendingEntry = new Pending(entry, 1);
            pending.computeIfAbsent(key, ignored -> new HashMap<>()).put(entry.id(), pendingEntry);
            deliveries.add(new Delivery(entry.id(), entry.envelope(), pendingEntry.deliveryCount()));
        }
        return deliveries;
    }

    synchronized List<Delivery> claimPending(String stream, String group, int count) {
        GroupKey key = new GroupKey(stream, group);
        List<Delivery> deliveries = new ArrayList<>();
        for (Pending pendingEntry : pending.computeIfAbsent(key, ignored -> new HashMap<>()).values()) {
            if (deliveries.size() >= count) {
                break;
            }
            pendingEntry.incrementDeliveryCount();
            deliveries.add(new Delivery(pendingEntry.entry().id(), pendingEntry.entry().envelope(), pendingEntry.deliveryCount()));
        }
        return deliveries;
    }

    synchronized void ack(String stream, String group, String messageId) {
        Map<String, Pending> groupPending = pending.get(new GroupKey(stream, group));
        if (groupPending != null) {
            groupPending.remove(messageId);
        }
    }

    synchronized void moveToDlq(String stream, String group, String messageId) {
        Map<String, Pending> groupPending = pending.get(new GroupKey(stream, group));
        if (groupPending == null) {
            return;
        }
        Pending entry = groupPending.remove(messageId);
        if (entry != null) {
            dlqs.computeIfAbsent(RedisStreamNames.dlqFor(stream), ignored -> new ArrayList<>()).add(entry.entry());
        }
    }

    synchronized boolean wasConsumed(String stream, String group, String eventId) {
        return consumedEventIds.computeIfAbsent(new GroupKey(stream, group), ignored -> new HashSet<>()).contains(eventId);
    }

    synchronized void recordConsumed(String stream, String group, String eventId) {
        consumedEventIds.computeIfAbsent(new GroupKey(stream, group), ignored -> new HashSet<>()).add(eventId);
    }

    public synchronized List<EventEnvelope> dlq(String stream) {
        return dlqs.getOrDefault(RedisStreamNames.dlqFor(stream), List.of()).stream().map(Entry::envelope).toList();
    }

    public synchronized Optional<Integer> pendingDeliveryCount(String stream, String group, String messageId) {
        return Optional.ofNullable(pending.get(new GroupKey(stream, group)))
            .map(messages -> messages.get(messageId))
            .map(Pending::deliveryCount);
    }

    record Delivery(String messageId, EventEnvelope envelope, int deliveryCount) {
    }

    private record GroupKey(String stream, String group) {
    }

    private record Entry(String id, EventEnvelope envelope) {
    }

    private static final class Pending {
        private final Entry entry;
        private int deliveryCount;

        Pending(Entry entry, int deliveryCount) {
            this.entry = entry;
            this.deliveryCount = deliveryCount;
        }

        Entry entry() {
            return entry;
        }

        int deliveryCount() {
            return deliveryCount;
        }

        void incrementDeliveryCount() {
            deliveryCount++;
        }
    }
}
