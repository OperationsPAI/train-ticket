# Payment Channel

Go implementation of the ADR-0003 Wave A simulated payment-channel boundary.

The service owns deterministic SIM channel orders, original-route refunds, daily
channel statements, and reconciliation discrepancies for `ALIPAY_SIM`,
`WECHAT_SIM`, and `UNIONPAY_SIM`. It persists aggregate snapshots with the shared
Go kit tables (`outbox`, `idempotency_records`, `processed_events`) and publishes
contract envelopes to `events:payment-channel` through the outbox relay.
