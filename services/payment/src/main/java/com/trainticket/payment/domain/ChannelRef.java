package com.trainticket.payment.domain;

public record ChannelRef(
    String channel,
    String channelOrderId,
    String channelRefundId,
    String channelTransactionId,
    String channelRefundTransactionId,
    String channelStatementId,
    String faultSeedRef
) {
    public ChannelRef withOrder(String orderId) {
        return new ChannelRef(channel, orderId, channelRefundId, channelTransactionId, channelRefundTransactionId, channelStatementId, faultSeedRef);
    }

    public ChannelRef withTransaction(String transactionId) {
        return new ChannelRef(channel, channelOrderId, channelRefundId, transactionId, channelRefundTransactionId, channelStatementId, faultSeedRef);
    }

    public ChannelRef withRefund(String refundId) {
        return new ChannelRef(channel, channelOrderId, refundId, channelTransactionId, channelRefundTransactionId, channelStatementId, faultSeedRef);
    }

    public ChannelRef withRefundTransaction(String transactionId) {
        return new ChannelRef(channel, channelOrderId, channelRefundId, channelTransactionId, transactionId, channelStatementId, faultSeedRef);
    }
}
