package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.Money;

public record ChannelStatementLineProjection(
    String statementLineId,
    String channelStatementId,
    String statementDate,
    String orderId,
    String paymentIntentId,
    String channelOrderId,
    Money actualAmount,
    String sourceEventId,
    // PAYMENT or REFUND. The channel's statement carries both, and the daily
    // reconciliation compares captures with payments only: a refund line is the
    // channel's answer to a platform refund, not to a capture.
    String lineType
) {
    public static final String PAYMENT = "PAYMENT";
    public static final String REFUND = "REFUND";

    public ChannelStatementLineProjection(
        String statementLineId,
        String channelStatementId,
        String statementDate,
        String orderId,
        String paymentIntentId,
        String channelOrderId,
        Money actualAmount,
        String sourceEventId
    ) {
        this(statementLineId, channelStatementId, statementDate, orderId, paymentIntentId, channelOrderId, actualAmount, sourceEventId, PAYMENT);
    }

    public boolean isPayment() {
        return PAYMENT.equalsIgnoreCase(lineType);
    }
}
