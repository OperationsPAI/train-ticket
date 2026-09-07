package com.trainticket.journeyorder.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.journeyorder.application.port.out.EventSubscriber;
import com.trainticket.journeyorder.application.service.InMemoryJourneyOrderStateRepository;
import com.trainticket.journeyorder.application.service.OrderManagementService;
import com.trainticket.journeyorder.domain.JourneyOrder;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Throughput and correctness guards for concurrent event handling.
 *
 * <p>{@code OrderManagementService.handle} used to be {@code synchronized}, which collapsed the
 * platform subscriber's {@code CONSUMER_THREADS} handler pool to an effective concurrency of 1 and
 * held the monitor for the whole database transaction. These tests pin the behaviour that replaced
 * it: parallelism across distinct aggregates, and optimistic-concurrency correctness on the same
 * aggregate.
 */
class OrderManagementServiceConcurrencyTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC);

    private static final int TIMEOUT_SECONDS = 10;

    private static final String EVENT_A = eventId(0x11);
    private static final String EVENT_B = eventId(0x12);

    /** Builds a spec-valid {@code evt-} + UUIDv7 identifier; envelopes reject anything else. */
    private static String eventId(int discriminator) {
        return String.format("evt-0194f2e0-7b3e-7610-8284-5c26e8b0d0%02x", discriminator);
    }

    /**
     * Events for DIFFERENT aggregates must be handled in parallel.
     *
     * <p>The repository blocks inside {@code findOrder} on a {@link CyclicBarrier} sized to the
     * number of concurrent handlers. The barrier can only trip if all handlers are inside
     * {@code handle} at the same time, so this asserts genuine overlap rather than merely asserting
     * that concurrent calls eventually finish.
     *
     * <p>Discrimination: restoring {@code synchronized} on {@code handle} makes every handler but
     * the first wait on the monitor, the barrier never trips, each handler fails with
     * {@link TimeoutException}, and the test fails.
     */
    @Test
    void eventsForDistinctAggregatesAreHandledInParallel() throws Exception {
        int handlers = 4;
        CyclicBarrier allInsideHandler = new CyclicBarrier(handlers);

        BarrierRepository repository = new BarrierRepository(allInsideHandler);
        OrderManagementService service = new OrderManagementService(
            envelope -> { }, FIXED_CLOCK, repository);

        List<String> orderIds = createOrders(service, handlers, "parallel");
        // Only block once the orders exist; creation itself must not trip the barrier.
        repository.armed.set(true);

        List<EventSubscriber.HandlerResult> results = runConcurrently(
            orderIds.stream()
                .map(orderId -> (Callable<EventSubscriber.HandlerResult>) () ->
                    service.handle(paymentCaptured(eventId(0x01 + orderIds.indexOf(orderId)), orderId)))
                .toList());

        for (EventSubscriber.HandlerResult result : results) {
            assertEquals(new EventSubscriber.Success(), result,
                "each distinct aggregate should be handled successfully and concurrently");
        }
        assertEquals(handlers, repository.barrierTrips.get(),
            "every handler must have been inside handle() simultaneously");
    }

    /**
     * Two events racing on the SAME aggregate: one wins, the loser must be told to retry.
     *
     * <p>Both threads read the aggregate at the same version before either writes, so exactly one
     * write can satisfy the optimistic concurrency check. The loser must surface as a
     * {@link EventSubscriber.TransientError} — a redelivery — not a swallowed success and not a
     * dead-letter.
     *
     * <p>Discrimination: this fails if {@code saveOrder} silently overwrites (last-write-wins, no
     * version check), because then both threads report success and one update is lost. It also
     * fails if the optimistic-concurrency exception were classified as {@code FatalError}, because
     * the losing event would be dead-lettered instead of retried.
     */
    @Test
    void concurrentEventsOnSameAggregateYieldOneWinnerAndOneRetryableLoser() throws Exception {
        int handlers = 2;
        CyclicBarrier readBeforeAnyWrite = new CyclicBarrier(handlers);

        BarrierRepository repository = new BarrierRepository(readBeforeAnyWrite);
        OrderManagementService service = new OrderManagementService(
            envelope -> { }, FIXED_CLOCK, repository);

        String orderId = createOrders(service, 1, "contended").getFirst();
        repository.armed.set(true);

        // Distinct eventIds: this is a genuine two-event race on one aggregate, not a duplicate.
        List<EventSubscriber.HandlerResult> results = runConcurrently(List.of(
            () -> service.handle(paymentCaptured(EVENT_A, orderId)),
            () -> service.handle(entitlementIssued(EVENT_B, orderId))));
        // Race is over; stop blocking so the assertions below can read the store freely.
        repository.armed.set(false);

        long successes = results.stream().filter(r -> r instanceof EventSubscriber.Success).count();
        long transients = results.stream().filter(r -> r instanceof EventSubscriber.TransientError).count();
        assertEquals(1, successes, "exactly one writer may win the version check");
        assertEquals(1, transients, "the losing writer must be retryable, got: " + results);

        // The loser must NOT have been recorded as processed, or the retry would be skipped.
        String loserEventId = repository.isEventProcessed(EVENT_A)
            ? EVENT_B : EVENT_A;
        assertTrue(!repository.isEventProcessed(loserEventId),
            "a losing event must not be marked processed, otherwise redelivery is a no-op");

        // Redelivery of the loser now succeeds against the winner's committed state.
        EventSubscriber.HandlerResult retry = service.handle(
            loserEventId.equals(EVENT_A)
                ? paymentCaptured(loserEventId, orderId)
                : entitlementIssued(loserEventId, orderId));
        assertEquals(new EventSubscriber.Success(), retry, "the retried event must apply cleanly");
        assertTrue(repository.isEventProcessed(loserEventId));

        // Both effects are now present on a single, uncorrupted aggregate.
        JourneyOrder finalOrder = repository.findOrder(orderId).orElseThrow().order();
        assertTrue(finalOrder.confirmationConditions().paymentConditionSatisfied(),
            "payment effect must survive the race");
        assertTrue(finalOrder.confirmationConditions().entitlementSummaryAccepted(),
            "entitlement effect must survive the race");
    }

    /**
     * The same eventId delivered twice concurrently must be applied at most once.
     *
     * <p>Both threads pass {@code isEventProcessed} before either records the event, so the
     * {@code processed_events} primary key (modelled here by the store's atomic add) is the only
     * thing preventing a double apply.
     *
     * <p>Discrimination: the barrier forces both threads past the dedup read before either writes,
     * so a non-atomic dedup or a missing version check would let both apply and the asserted
     * single-application below would fail.
     */
    @Test
    void duplicateDeliveryOfSameEventIdIsAppliedAtMostOnce() throws Exception {
        int handlers = 2;
        CyclicBarrier bothPastDedupRead = new CyclicBarrier(handlers);

        BarrierRepository repository = new BarrierRepository(bothPastDedupRead);
        OrderManagementService service = new OrderManagementService(
            envelope -> { }, FIXED_CLOCK, repository);

        String orderId = createOrders(service, 1, "duplicate").getFirst();
        repository.armed.set(true);

        String eventId = eventId(0x21);
        EventEnvelope duplicate = paymentCaptured(eventId, orderId);
        List<EventSubscriber.HandlerResult> results = runConcurrently(List.of(
            () -> service.handle(duplicate),
            () -> service.handle(duplicate)));

        long successes = results.stream().filter(r -> r instanceof EventSubscriber.Success).count();
        assertTrue(successes >= 1, "at least one delivery must apply, got: " + results);
        for (EventSubscriber.HandlerResult result : results) {
            assertTrue(result instanceof EventSubscriber.Success
                    || result instanceof EventSubscriber.TransientError,
                "duplicate delivery must never be a FatalError/dead-letter, got: " + result);
        }
        assertTrue(repository.isEventProcessed(eventId));

        // The payment effect must be recorded exactly once: version advanced by exactly one write.
        JourneyOrder finalOrder = repository.findOrder(orderId).orElseThrow().order();
        assertEquals(2L, finalOrder.version(),
            "create + exactly one applied delivery; a double apply would leave version 3");
        assertTrue(finalOrder.confirmationConditions().paymentConditionSatisfied());
    }

    /**
     * Guards the root cause directly: {@code handle} must not hold the service monitor.
     *
     * <p>One thread parks inside {@code handle}; a second thread must still be able to acquire the
     * {@code OrderManagementService} instance lock. If {@code handle} were {@code synchronized}
     * again, that lock would be held for the whole transaction and this would time out.
     */
    @Test
    void handleDoesNotHoldTheServiceMonitor() throws Exception {
        CountDownLatch insideHandler = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);

        InMemoryJourneyOrderStateRepository repository = new InMemoryJourneyOrderStateRepository() {
            @Override
            public boolean isEventProcessed(String eventId) {
                insideHandler.countDown();
                try {
                    releaseHandler.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return super.isEventProcessed(eventId);
            }
        };
        OrderManagementService service = new OrderManagementService(
            envelope -> { }, FIXED_CLOCK, repository);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> parked = executor.submit(() ->
                service.handle(paymentCaptured(eventId(0x31), "ord-does-not-exist")));
            assertTrue(insideHandler.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "handler thread never entered handle()");

            Future<Boolean> lockProbe = executor.submit(() -> {
                synchronized (service) {
                    return Boolean.TRUE;
                }
            });
            assertEquals(Boolean.TRUE, lockProbe.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "handle() must not hold the service instance monitor");

            releaseHandler.countDown();
            parked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseHandler.countDown();
            executor.shutdownNow();
        }
    }

    /** Sanity check that the in-memory double really enforces optimistic concurrency. */
    @Test
    void inMemoryRepositoryEnforcesVersionCheckAndCopiesOnRead() {
        InMemoryJourneyOrderStateRepository repository = new InMemoryJourneyOrderStateRepository();
        OrderManagementService service = new OrderManagementService(
            envelope -> { }, FIXED_CLOCK, repository);
        String orderId = createOrders(service, 1, "occ").getFirst();

        JourneyOrder first = repository.findOrder(orderId).orElseThrow().order();
        JourneyOrder second = repository.findOrder(orderId).orElseThrow().order();
        assertNotEquals(System.identityHashCode(first), System.identityHashCode(second),
            "each read must return an independent aggregate instance");
        assertEquals(first.version(), second.version());

        repository.saveOrder(first, "idem-occ");
        try {
            repository.saveOrder(second, "idem-occ");
            fail("stale write should have been rejected by the version check");
        } catch (com.trainticket.platformkit.persistence.OptimisticConcurrencyException expected) {
            assertTrue(expected.getMessage().contains(orderId));
        }
    }

    // --- helpers -------------------------------------------------------------------------

    /**
     * Repository double that parks every {@code findOrder}/{@code isEventProcessed} caller on a
     * barrier, forcing all participating handler threads to overlap before any of them writes.
     */
    private static final class BarrierRepository extends InMemoryJourneyOrderStateRepository {
        private final CyclicBarrier barrier;
        private final java.util.concurrent.atomic.AtomicBoolean armed =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        private final AtomicInteger barrierTrips = new AtomicInteger();

        private BarrierRepository(CyclicBarrier barrier) {
            this.barrier = barrier;
        }

        @Override
        public boolean isEventProcessed(String eventId) {
            boolean processed = super.isEventProcessed(eventId);
            if (!processed) {
                await();
            }
            return processed;
        }

        private void await() {
            if (!armed.get()) {
                return;
            }
            try {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                barrierTrips.incrementAndGet();
            } catch (TimeoutException | BrokenBarrierException exception) {
                throw new IllegalStateException(
                    "handlers did not run concurrently; handle() appears to be serialised", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
    }

    private static List<String> createOrders(OrderManagementService service, int count, String tag) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(i -> service.createOrder(
                new JourneyOrderRequest("account-" + tag + "-" + i, "offer-" + tag + "-" + i, 1,
                    List.of("tvl-1"), List.of("seg-1")),
                "idem-" + tag + "-" + i,
                "corr-" + tag).orderId())
            .toList();
    }

    private static List<EventSubscriber.HandlerResult> runConcurrently(
            List<Callable<EventSubscriber.HandlerResult>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        try {
            List<Future<EventSubscriber.HandlerResult>> futures = tasks.stream()
                .map(executor::submit)
                .toList();
            List<EventSubscriber.HandlerResult> results = new java.util.ArrayList<>();
            for (Future<EventSubscriber.HandlerResult> future : futures) {
                try {
                    results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (Exception exception) {
                    failures.add(exception);
                }
            }
            if (!failures.isEmpty()) {
                AssertionError error = new AssertionError(
                    "concurrent handlers did not all complete: " + failures.peek());
                failures.forEach(error::addSuppressed);
                throw error;
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static EventEnvelope paymentCaptured(String eventId, String orderId) {
        return new EventEnvelope(
            eventId,
            "PaymentCaptured",
            Instant.parse("2026-07-05T10:01:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0ce99",
            "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0ce98",
            "payment",
            1,
            Map.of(
                "orderId", orderId,
                "businessRef", orderId,
                "paymentIntentId", "pi-" + eventId,
                "capturedAmount", Map.of("currency", "CNY", "minorUnits", 10000),
                "channel", "SIM",
                "channelTransactionId", "ch-" + eventId
            )
        );
    }

    private static EventEnvelope entitlementIssued(String eventId, String orderId) {
        return new EventEnvelope(
            eventId,
            "EntitlementIssued",
            Instant.parse("2026-07-05T10:01:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0ce99",
            "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0ce98",
            "entitlement-ticketing",
            1,
            Map.of(
                "entitlementId", "ent-" + eventId,
                "segmentBookingId", "sb-" + eventId,
                "journeyOrderId", orderId,
                "travelerRef", "tvl-1",
                "segmentRef", "seg-1",
                "issuePurpose", "INITIAL",
                "credentialNo", "ticket-1",
                "credentialType", "E_TICKET",
                "issuedAt", "2026-07-05T10:01:00Z"
            )
        );
    }
}
