package com.trainticket.payment.adapters.messaging;

final class RedisStreamNames {
    static final String BOOKING_ORCHESTRATION_STREAM = "events:booking-orchestration";
    static final String POST_SALES_STREAM = "events:post-sales";
    static final String PAYMENT_CHANNEL_STREAM = "events:payment-channel";
    static final String ANCILLARY_SERVICE_STREAM = "events:ancillary-service";
    static final String PAYMENT_GROUP = "payment";

    private RedisStreamNames() {
    }

    static String streamForProducer(String producer) {
        return "events:" + producer;
    }

    static String dlqFor(String stream) {
        return stream + ":dlq";
    }
}
