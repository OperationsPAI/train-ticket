# Payment channels and refunds

## Implementation

Payment is a Java service that manages payment intents, channel callbacks, refunds and late payment cases.
The default router selects eligible `ALIPAY`, `WECHAT_PAY` and `UNIONPAY` channels and maps them to the corresponding SIM channels in Payment Channel.
Selection considers the requested channel, enabled state, amount limit and configured weights.
The scheduled timeout sweeper expires open intents once they reach `expiresAt` and publishes `PaymentIntentExpired` and `PaymentTimedOut`.
Refunds support partial amounts, preserve the original channel order and transaction, and track the captured and refunded balance.
Late captures preserve the cancelled or expired intent state and have a separate recorded refund resolution path.

## Interfaces and sources

[HTTP API](../08-contracts/api/payment.md) and [controller](../../services/payment/src/main/java/com/trainticket/payment/adapters/http/PaymentController.java) expose `/api/v1/payment-intents`, `/api/v1/refunds`, business reference lookup and late payment resolution.
[Channel router](../../services/payment/src/main/java/com/trainticket/payment/domain/ChannelRouter.java), [payment intent](../../services/payment/src/main/java/com/trainticket/payment/domain/PaymentIntent.java), [command service](../../services/payment/src/main/java/com/trainticket/payment/application/PaymentCommandService.java) and [timeout sweeper](../../services/payment/src/main/java/com/trainticket/payment/application/PaymentTimeoutSweeper.java) define routing and lifecycle behavior.
[Service README](../../services/payment/README.md) describes funds ownership and callback handling.

## Boundaries

Default routing covers the three SIM channels and does not imply external provider settlement.
Payment publishes funds facts; Journey Order and the fulfillment services own order and ticket effects.
Post Sales complaint compensation uses Wallet Promotion and is separate from original payment refunds.
Deployment and verification evidence, including differences from workspace code, are recorded in [current status](../00-current-status.md) for Helm revision 279.
Remaining work is maintained only in [TODO](../TODO.md).
