# Payment Enrichment — Multi-Channel Payment & Refund

## Context

**Service**: payment (Go, `services/payment/`)
**Current state**: 1,295-line domain with PaymentIntent, capture/refund lifecycle. Single simulated channel. No timeout handling, no multi-channel routing, no partial refund.

## Requirements

### R1: Multi-Channel Payment Routing

```
Channels:
  ALIPAY:      weight=40%, max_amount=500000, timeout=30s
  WECHAT_PAY:  weight=35%, max_amount=200000, timeout=30s
  UNIONPAY:    weight=15%, max_amount=1000000, timeout=60s
  APPLE_PAY:   weight=5%,  max_amount=100000, timeout=30s
  BALANCE:     weight=5%,  max_amount=50000,  timeout=5s (wallet)

Channel selection:
  - User specifies preferred channel
  - If preferred unavailable → fallback by weight
  - If amount exceeds channel max → reject or split (future)
```

**Domain model**:
- `PaymentChannel` struct: `channelId`, `channelType`, `maxAmountMinor`, `timeoutSeconds`, `enabled`, `weight`
- `ChannelRouter`: selects channel based on preference + amount + availability
- `PaymentIntent` gains `channelRef` field

### R2: Payment Timeout & Auto-Cancel

```
Timeout flow:
  1. PaymentIntent created → timer starts (channel-specific timeout)
  2. If not captured within timeout → auto-cancel intent
  3. Auto-cancel publishes PaymentTimedOut event
  4. Journey-order consumes PaymentTimedOut → cancel order, release capacity

Timeout values:
  Standard channels: 30 seconds
  UnionPay: 60 seconds
  Balance: 5 seconds
```

**Domain model**:
- `PaymentIntent` gains `expiresAt time.Time`
- `ExpirePaymentIntent` command
- `PaymentTimedOut` event: `intentId`, `orderId`, `reason: "TIMEOUT"`
- Background goroutine sweeps expired intents

### R3: Refund to Original Channel

```
Refund routing rules:
  - Refund MUST go back to original payment channel
  - If original channel unavailable → hold refund, alert customer service
  - Partial refund allowed (e.g., refund ticket but keep insurance)
  - Refund amount cannot exceed original capture amount

Partial refund:
  - Track refunded amount per intent
  - remaining_refundable = captured_amount - total_refunded
  - Multiple partial refunds allowed until remaining = 0
```

**Domain model**:
- `RefundRequest` struct: `intentId`, `amountMinor`, `reason`, `componentRefs`
- `PaymentIntent` gains: `totalRefundedMinor`, `refundHistory []RefundRecord`
- `RefundRecord`: `refundId`, `amountMinor`, `channelRef`, `status`, `createdAt`
- Invariant: `totalRefundedMinor <= capturedAmountMinor`

### R4: Payment Reconciliation Events

```
Events for downstream:
  PaymentCaptured:   { intentId, orderId, channelRef, amountMinor, capturedAt }
  PaymentRefunded:   { intentId, orderId, refundId, amountMinor, channelRef }
  PaymentTimedOut:   { intentId, orderId, expiresAt }
  PaymentFailed:     { intentId, orderId, reason, channelRef }
```

## Test Criteria

1. Payment with Alipay channel → captured via Alipay, refund returns to Alipay
2. Payment timeout → auto-cancel, PaymentTimedOut event published
3. Partial refund 50% → remaining_refundable is 50%
4. Second partial refund of remaining → fully refunded
5. Refund exceeding captured amount → REJECT
6. Channel max exceeded → reject with AMOUNT_EXCEEDS_CHANNEL_LIMIT

## Files to Modify

- `services/payment/internal/domain/` — PaymentChannel, ChannelRouter, timeout, partial refund
- `services/payment/internal/application/`
- `services/payment/internal/adapters/api/`
- `services/payment/migrations/`
