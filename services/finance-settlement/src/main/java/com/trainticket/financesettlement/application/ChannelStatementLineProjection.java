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
    String sourceEventId
) {}
