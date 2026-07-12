package com.trainticket.bookingorchestration.application;

public interface ConsumedEventRepository {
    boolean recordIfNew(String eventId, String stream);
}
