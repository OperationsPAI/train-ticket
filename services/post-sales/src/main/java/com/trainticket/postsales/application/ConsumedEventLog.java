package com.trainticket.postsales.application;

public interface ConsumedEventLog {
    boolean recordIfFirstSeen(String eventId);
}
