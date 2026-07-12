package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.ConsumedEventLog;

public interface ConsumedEventLogRepository {
    boolean existsByEventId(String eventId);
    void save(ConsumedEventLog log);
    default boolean recordIfNew(ConsumedEventLog log) {
        if (existsByEventId(log.eventId())) {
            return false;
        }
        save(log);
        return true;
    }
    default boolean guardsTransactionally() { return false; }
}

