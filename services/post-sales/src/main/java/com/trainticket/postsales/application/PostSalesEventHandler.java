package com.trainticket.postsales.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PostSalesEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostSalesEventHandler.class);

    private final ConsumedEventLog consumedEventLog;
    private final PostSalesApplicationService applicationService;
    private final PostSalesExternalEventPolicy externalEventPolicy;
    private final TransactionTemplate transactionTemplate;

    public PostSalesEventHandler(ConsumedEventLog consumedEventLog, PostSalesApplicationService applicationService) {
        this(consumedEventLog, applicationService, new PostSalesExternalEventPolicy(applicationService), (PlatformTransactionManager) null);
    }

    @Autowired
    public PostSalesEventHandler(
        ConsumedEventLog consumedEventLog,
        PostSalesApplicationService applicationService,
        PostSalesExternalEventPolicy externalEventPolicy,
        Optional<PlatformTransactionManager> transactionManager
    ) {
        this(consumedEventLog, applicationService, externalEventPolicy, transactionManager.orElse(null));
    }

    PostSalesEventHandler(
        ConsumedEventLog consumedEventLog,
        PostSalesApplicationService applicationService,
        PostSalesExternalEventPolicy externalEventPolicy,
        PlatformTransactionManager transactionManager
    ) {
        this.consumedEventLog = consumedEventLog;
        this.applicationService = applicationService;
        this.externalEventPolicy = externalEventPolicy;
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
            if ("JourneyOrderCreated".equals(envelope.eventType()) || "JourneyOrderConfirmed".equals(envelope.eventType())) {
                policyContext(envelope.payload()).ifPresent(applicationService::recordPolicyContext);
            } else if ("JourneyOrderPostSalesAdjusted".equals(envelope.eventType())) {
                policyContext(envelope.payload()).ifPresent(applicationService::recordPolicyContext);
            } else if ("CapacityReleased".equals(envelope.eventType())) {
                String segmentBookingRef = segmentBookingRef(envelope.payload());
                if (segmentBookingRef != null) {
                    applicationService.applyForSegmentBooking(segmentBookingRef, envelope.eventId(), envelope.correlationId());
                }
            } else if (externalEventPolicy.handles(envelope.eventType())) {
                externalEventPolicy.handle(envelope);
            } else {
                LOGGER.warn("ack-skip post-sales event={} eventId={} producer={} reason=UNKNOWN_EVENT_TYPE",
                    envelope.eventType(), envelope.eventId(), envelope.producer());
            }
            return EventSubscriber.HandlerResult.SUCCESS;
        } catch (RuntimeException exception) {
            if (!transactional) {
                consumedEventLog.discard(envelope.eventId());
            }
            return EventSubscriber.HandlerResult.TRANSIENT_FAILURE;
        }
    }

    private static Optional<PostSalesPolicyContext> policyContext(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        return PostSalesPolicyContextMapper.fromEventPayload(map);
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
