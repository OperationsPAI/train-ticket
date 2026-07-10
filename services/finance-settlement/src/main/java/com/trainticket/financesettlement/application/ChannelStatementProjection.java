package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.Money;
import java.time.Instant;

public record ChannelStatementProjection(
    String channelStatementId,
    String channel,
    String statementDate,
    String currency,
    String seedVersion,
    int lineCount,
    Money grossPaymentAmount,
    Money grossRefundAmount,
    Money feeAmount,
    String statementHash,
    String status,
    Instant generatedAt,
    Instant frozenAt,
    String sourceEventId
) {}
