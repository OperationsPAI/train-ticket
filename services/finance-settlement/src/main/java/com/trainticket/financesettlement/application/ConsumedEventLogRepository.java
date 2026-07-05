package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.ConsumedEventLog;

public interface ConsumedEventLogRepository {
    boolean existsByEventId(String eventId);
    void save(ConsumedEventLog log);
}
