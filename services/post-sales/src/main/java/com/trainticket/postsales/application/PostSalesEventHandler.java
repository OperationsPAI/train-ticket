package com.trainticket.postsales.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PostSalesEventHandler {
    private final ConsumedEventLog consumedEventLog;
    private final PostSalesApplicationService applicationService;
    private final TransactionTemplate transactionTemplate;

    public PostSalesEventHandler(ConsumedEventLog consumedEventLog, PostSalesApplicationService applicationService) {
        this(consumedEventLog, applicationService, (PlatformTransactionManager) null);
    }

    @Autowired
    public PostSalesEventHandler(
        ConsumedEventLog consumedEventLog,
        PostSalesApplicationService applicationService,
        Optional<PlatformTransactionManager> transactionManager
    ) {
        this(consumedEventLog, applicationService, transactionManager.orElse(null));
    }

    PostSalesEventHandler(
        ConsumedEventLog consumedEventLog,
        PostSalesApplicationService applicationService,
        PlatformTransactionManager transactionManager
    ) {
        this.consumedEventLog = consumedEventLog;
        this.applicationService = applicationService;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    public EventSubscriber.HandlerResult handle(EventEnvelope envelope) {
        if (transactionTemplate == null) {
            return handleInCurrentThread(envelope, false);
        }
        return transactionTemplate.execute(status -> {
            EventSubscriber.HandlerResult result = handleInCurrentThread(envelope, true);
            if (result == EventSubscriber.HandlerResult.TRANSIENT_FAILURE) {
                status.setRollbackOnly();
            }
            return result;
        });
    }

    private EventSubscriber.HandlerResult handleInCurrentThread(EventEnvelope envelope, boolean transactional) {
        try {
            if (!consumedEventLog.recordIfFirstSeen(envelope.eventId())) {
                return EventSubscriber.HandlerResult.SUCCESS;
            }
            if ("CapacityReleased".equals(envelope.eventType())) {
                String segmentBookingRef = segmentBookingRef(envelope.payload());
                if (segmentBookingRef != null) {
                    applicationService.applyForSegmentBooking(segmentBookingRef, envelope.eventId(), envelope.correlationId());
                }
            }
            return EventSubscriber.HandlerResult.SUCCESS;
        } catch (RuntimeException exception) {
            if (!transactional) {
                consumedEventLog.discard(envelope.eventId());
            }
            return EventSubscriber.HandlerResult.TRANSIENT_FAILURE;
        }
    }

    private static String segmentBookingRef(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return null;
        }
        Object direct = map.get("segmentBookingId");
        if (direct instanceof String text && !text.isBlank()) {
            return text;
        }
        Object references = map.get("references");
        if (references instanceof Map<?, ?> refs) {
            Object nested = refs.get("segmentBookingRef");
            if (nested instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }
}
