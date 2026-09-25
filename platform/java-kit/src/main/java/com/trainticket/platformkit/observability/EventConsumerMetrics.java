package com.trainticket.platformkit.observability;

/**
 * Low-cardinality operational metrics for one Redis event delivery.
 *
 * <p>The enums are deliberately closed. Stream names, consumer names, event types and business
 * identifiers are useful in traces and logs, but using them as metric attributes would create a
 * series per integration or entity. These outcomes are sufficient to distinguish healthy
 * processing, deduplication, retry pressure and poison-message handling without that cardinality.
 */
public interface EventConsumerMetrics {
    enum Outcome {
        SUCCESS("success"),
        DUPLICATE("duplicate"),
        RETRY("retry"),
        DEAD_LETTER("dead_letter");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    enum FailureType {
        HANDLER_EXCEPTION("handler_exception"),
        TRANSIENT_RESULT("transient_result"),
        FATAL_RESULT("fatal_result"),
        MISSING_ENVELOPE("missing_envelope"),
        INVALID_ENVELOPE("invalid_envelope"),
        REDELIVERY_EXHAUSTED("redelivery_exhausted"),
        DLQ_WRITE("dlq_write");

        private final String value;

        FailureType(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    void recordOutcome(Outcome outcome);

    void recordFailure(FailureType failureType);

    void recordDeadLetter(FailureType reason);
}
