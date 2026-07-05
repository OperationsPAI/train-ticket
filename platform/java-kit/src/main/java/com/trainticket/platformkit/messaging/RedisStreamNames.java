package com.trainticket.platformkit.messaging;

public final class RedisStreamNames {
    private RedisStreamNames() {
    }

    public static String streamForProducer(String producer) {
        if (producer == null || producer.isBlank()) {
            throw new IllegalArgumentException("producer is required");
        }
        return "events:" + producer;
    }

    public static String dlqFor(String stream) {
        if (stream == null || stream.isBlank()) {
            throw new IllegalArgumentException("stream is required");
        }
        return stream.endsWith(":dlq") ? stream : stream + ":dlq";
    }
}
