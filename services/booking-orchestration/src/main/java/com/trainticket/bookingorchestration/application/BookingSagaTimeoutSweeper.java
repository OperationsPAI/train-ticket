package com.trainticket.bookingorchestration.application;

import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fails the saga steps whose recorded deadline passed while their events never arrived, so a
 * reservation that is never confirmed reaches a terminal saga state instead of staying PENDING.
 */
@Component
public final class BookingSagaTimeoutSweeper {
    private static final Logger LOGGER = LoggerFactory.getLogger(BookingSagaTimeoutSweeper.class);
    private static final int BATCH_SIZE = 200;

    private final BookingOrchestrationService service;

    public BookingSagaTimeoutSweeper(BookingOrchestrationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${booking-orchestration.step-timeout-sweeper-delay-ms:1000}")
    public void sweepTimedOutSteps() {
        for (BookingSagaStepDeadlineRepository.BookingSagaStepDeadline due : service.findDueStepDeadlines(BATCH_SIZE)) {
            try {
                if (service.expireTimedOutStep(due.sagaId(), due.stepIdempotencyKey())) {
                    LOGGER.info("Failed booking saga {} step {} past its deadline",
                        due.sagaId(), due.stepIdempotencyKey());
                }
            } catch (OptimisticConcurrencyException race) {
                // Another writer committed between the read and the write, so this sweep applied
                // nothing and the next tick reads the state that writer stored.
                LOGGER.info("Booking saga {} changed while its step {} deadline was swept",
                    due.sagaId(), due.stepIdempotencyKey());
            }
        }
    }
}
