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
            // Reject uninteresting event types BEFORE touching the database.
            //
            // recordIfFirstSeen writes to processed_events, so the original order
            // charged a DB round-trip to every event on every subscribed stream --
            // including the ones this service does nothing with. That is the
            // overwhelming majority: post-sales subscribes to all of
            // events:capacity-availability for CapacityReleased alone, and a
            // 300-event sample of that stream contained zero of them. On the live
            // cluster it was 7325 of 12606 pending messages.
            //
            // The cost was not academic. Four consumer threads each doing a write
            // per ignored event starved the HTTP thread badly enough that /readyz
            // could not answer within a 10s probe timeout, and post-sales was
            // killed and restarted repeatedly -- which grew the backlog further.
            //
            // Dedup is not weakened by this: an event that reaches no handler has
            // no side effect to deduplicate. The log is there to make replaying one
            // idempotent, and skipping it for a no-op is exactly equivalent.
            if (!isInteresting(envelope.eventType())) {
                LOGGER.debug("ack-skip post-sales event={} eventId={} producer={} reason=UNKNOWN_EVENT_TYPE",
                    envelope.eventType(), envelope.eventId(), envelope.producer());
                return EventSubscriber.HandlerResult.SUCCESS;
            }
            if (!consumedEventLog.recordIfFirstSeen(envelope.eventId())) {
                return EventSubscriber.HandlerResult.SUCCESS;
            }
            if ("JourneyOrderCreated".equals(envelope.eventType()) || "JourneyOrderConfirmed".equals(envelope.eventType())) {
                recordPolicyContextOrWarn(envelope);
            } else if ("JourneyOrderPostSalesAdjusted".equals(envelope.eventType())) {
                recordPolicyContextOrWarn(envelope);
            } else if ("CapacityReleased".equals(envelope.eventType())) {
                String segmentBookingRef = segmentBookingRef(envelope.payload());
                if (segmentBookingRef != null) {
                    applicationService.applyForSegmentBooking(segmentBookingRef, envelope.eventId(), envelope.correlationId());
                }
            } else if (externalEventPolicy.handles(envelope.eventType())) {
                externalEventPolicy.handle(envelope);
            } else {
                // DEBUG, not WARN. post-sales subscribes to whole streams but only
                // acts on a few event types from each, so every CapacityHeld,
                // DriverAssigned, RideStarted and so on lands here in normal
                // operation -- it is not an anomaly, it is the subscription model.
                //
                // At WARN this was 30% of the service's log output (605 lines in a
                // 2000-line sample) and it actively caused an outage: raising
                // CONSUMER_THREADS to 4 multiplied the volume by four, and the
                // logging plus the backlog work saturated the 500m CPU limit until
                // the HTTP thread could not answer /healthz, so the startup probe
                // failed and kubelet restarted the pod in a loop. A log line for a
                // non-event should never be able to do that.
                LOGGER.debug("ack-skip post-sales event={} eventId={} producer={} reason=UNKNOWN_EVENT_TYPE",
                    envelope.eventType(), envelope.eventId(), envelope.producer());
            }
            return EventSubscriber.HandlerResult.SUCCESS;
        } catch (RuntimeException exception) {
            if (!transactional) {
                consumedEventLog.discard(envelope.eventId());
            }
            // The exception used to be swallowed entirely: TRANSIENT_FAILURE was
            // returned with no log line, so the message stayed pending and was
            // redelivered forever with nothing anywhere saying why. The live
            // cluster had 3522 pending messages on events:journey-order under this
            // consumer group, which is how JourneyOrderCreated never reached
            // recordPolicyContext for thousands of orders -- and the downstream
            // effect (every refund for those orders quoting zero) looked like a
            // pricing bug rather than a stuck consumer.
            //
            // Logged at ERROR with the exception: a handler that cannot make
            // progress is not a routine condition, and the stack trace is the only
            // thing that distinguishes a poison message from a dependency outage.
            LOGGER.error("post-sales handler FAILED event={} eventId={} producer={} transactional={} "
                    + "-- returning TRANSIENT_FAILURE, so this message stays pending and will be "
                    + "redelivered. If this repeats for the same eventId the consumer is stuck.",
                envelope.eventType(), envelope.eventId(), envelope.producer(), transactional, exception);
            return EventSubscriber.HandlerResult.TRANSIENT_FAILURE;
        }
    }

    /**
     * The event types the dispatch below actually acts on.
     *
     * MUST stay in step with that dispatch. Listing a type here that no branch
     * handles costs a wasted database write; OMITTING one that a branch handles
     * silently drops the event, which is far worse -- so the set is derived from
     * externalEventPolicy where it can be, and the literals are the exact strings
     * the if-chain compares against. There is a test that walks the dispatch's
     * own conditions against this predicate.
     */
    private boolean isInteresting(String eventType) {
        return switch (eventType) {
            case "JourneyOrderCreated", "JourneyOrderConfirmed",
                 "JourneyOrderPostSalesAdjusted", "CapacityReleased" -> true;
            default -> externalEventPolicy.handles(eventType);
        };
    }

    private static Optional<PostSalesPolicyContext> policyContext(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        return PostSalesPolicyContextMapper.fromEventPayload(map);
    }

    /**
     * Records the policy context, or says why it could not.
     *
     * The `ifPresent` this replaces was the first link in a five-step silent
     * chain: mapper returns empty -> nothing stored -> the refund path finds no
     * context -> it falls back to departureTime=now -> AFTER_DEPARTURE, 100%
     * penalty, zero refund -> payment skips the zero-amount refund without
     * logging. Nobody was refunded and no component reported a problem.
     *
     * The mapper returns empty when the event carries no resolvable departure
     * (no segments[].departureTime and no top-level departureTime/departureAt) or
     * no order id, so name both possibilities here -- that is the actionable part.
     */
    private void recordPolicyContextOrWarn(EventEnvelope envelope) {
        Optional<PostSalesPolicyContext> context = policyContext(envelope.payload());
        if (context.isPresent()) {
            PostSalesPolicyContext ctx = context.get();
            LOGGER.debug("post-sales policy context stored from event={} eventId={} order={} departureTime={} originalFare={}",
                envelope.eventType(), envelope.eventId(), ctx.journeyOrderId(), ctx.departureTime(), ctx.originalFare());
            applicationService.recordPolicyContext(ctx);
            return;
        }
        LOGGER.warn("post-sales could NOT build a policy context from event={} eventId={} producer={} "
                + "-- the payload has no resolvable departure time (segments[].departureTime / "
                + "departureTime / departureAt) or no order id. Refunds for this order will fall back "
                + "to departureTime=now and quote a ZERO refund. payloadKeys={}",
            envelope.eventType(), envelope.eventId(), envelope.producer(),
            envelope.payload() instanceof Map<?, ?> m ? m.keySet() : "<not-a-map>");
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
