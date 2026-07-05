package com.trainticket.financesettlement.adapters.messaging;

import com.trainticket.financesettlement.application.EventSubscriber;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "finance.messaging.redis.enabled", havingValue = "false", matchIfMissing = false)
public class InMemoryEventSubscriber implements EventSubscriber {
    @Override
    public void subscribe(List<String> streams, String group, String consumerName, EventHandler handler) {
    }
}
