package com.trainticket.postsales.adapters.messaging;

final class RedisStreamNames {
    static final String CONSUMER_GROUP = "post-sales";
    static final String PRODUCER = "post-sales";

    private RedisStreamNames() {
    }

    static String forProducer(String producer) {
        return "events:" + producer;
    }

    static String dlq(String stream) {
        return stream + ":dlq";
    }
}
