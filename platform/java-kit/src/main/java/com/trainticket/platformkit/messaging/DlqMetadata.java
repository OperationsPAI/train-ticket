package com.trainticket.platformkit.messaging;

public record DlqMetadata(
    String consumerGroup,
    String consumerName,
    String failureReason,
    int attempts,
    String deadLetteredAt
) {
}
