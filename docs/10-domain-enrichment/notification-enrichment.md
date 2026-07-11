# Notification Enrichment — Multi-Channel Delivery & Rate Limiting

## Context

**Service**: notification (Java, `services/notification/`)
**Current state**: 786-line domain, basic event-to-email mapping via SMTP. No multi-channel, no templates, no rate limiting, no delivery tracking.

## Requirements

### R1: Multi-Channel Delivery with Fallback

```
Channels (priority order):
  PUSH:   app push notification (fastest, cheapest)
  SMS:    text message (reliable, higher cost)
  EMAIL:  email via SMTP (slowest, lowest cost)
  
Fallback chain:
  1. Try primary channel (user preference or PUSH default)
  2. If delivery fails or channel unavailable → try next in chain
  3. If all fail → mark as FAILED, alert customer service

Channel availability:
  - PUSH: requires device token (not all users have app)
  - SMS: requires phone number
  - EMAIL: requires email address (always available via account)
```

**Domain model**:
- `NotificationChannel` enum: PUSH, SMS, EMAIL
- `DeliveryAttempt`: `channelUsed`, `attemptedAt`, `status` (SENT, DELIVERED, FAILED, BOUNCED)
- `ChannelFallbackChain`: ordered list of channels to try
- `NotificationRequest` aggregate: tracks delivery attempts across channels

### R2: Template Rendering

```
Template types:
  ORDER_CONFIRMED:     "您的订单 {orderId} 已确认，{origin}→{destination}，{departureTime} 出发"
  PAYMENT_REMINDER:    "订单 {orderId} 待支付，请在 {expiresAt} 前完成付款"
  TICKET_ISSUED:       "电子客票已出票：{trainNumber} {seatInfo}，请凭身份证进站"
  DELAY_ALERT:         "您乘坐的 {trainNumber} 次列车预计晚点 {delayMinutes} 分钟"
  REFUND_COMPLETED:    "退款 {amount} 元已原路返回，预计 {arrivalDays} 个工作日到账"
  WAITLIST_PROMOTED:   "候补购票成功！{origin}→{destination} {departureDate}，请在 15 分钟内确认"
  DISRUPTION_REBOOK:   "由于列车取消，已为您改签至 {newTrainNumber} {newDepartureTime}"

Each template has:
  - subject (for email)
  - body (with {variable} placeholders)
  - SMS variant (shorter, max 70 chars)
  - push title + body
```

**Domain model**:
- `NotificationTemplate`: `templateId`, `templateType`, `channel`, `subjectTemplate`, `bodyTemplate`
- `TemplateRenderer`: resolves `{variables}` from event payload
- Templates stored in code (not DB) for simplicity

### R3: Rate Limiting

```
Rate limits per user:
  PUSH:   max 10 per hour
  SMS:    max 5 per day
  EMAIL:  max 20 per day
  
Global limits:
  SMS:    max 1000 per minute (carrier throttle)
  
Aggregation:
  - Multiple events within 5 minutes for same order → merge into single notification
  - Example: ORDER_CONFIRMED + PAYMENT_REMINDER within 5min → send only ORDER_CONFIRMED
```

**Domain model**:
- `RateLimiter`: per-user and global rate tracking
- `NotificationAggregator`: buffers events, merges within time window
- `RateLimitExceeded` → defer notification, retry later

### R4: Delivery Tracking

```
Delivery statuses:
  QUEUED:    notification created, pending send
  SENT:      handed off to channel provider
  DELIVERED: confirmed delivered (push ACK, email open, SMS delivery report)
  FAILED:    delivery failed (bounced, invalid token, etc.)
  
Tracking:
  - Each notification has a delivery trail
  - Failed deliveries trigger fallback
  - Delivery stats exposed via API for monitoring
```

**Domain model**:
- `DeliveryTrail`: list of `DeliveryAttempt` for each notification
- `NotificationStatus` enum: QUEUED, SENDING, SENT, DELIVERED, FAILED
- API: `GET /api/v1/notifications/{id}/trail` — delivery history

## Events Consumed
- `events:journey-order` → ORDER_CONFIRMED, ORDER_CANCELLED
- `events:payment` → PAYMENT_REMINDER, PAYMENT_CAPTURED
- `events:entitlement-ticketing` → TICKET_ISSUED
- `events:post-sales` → REFUND_COMPLETED
- `events:disruption-recovery` → DELAY_ALERT, DISRUPTION_REBOOK
- `events:waitlist` → WAITLIST_PROMOTED

## Events Produced
- `NotificationSent` — recipientId, channel, templateType
- `NotificationDelivered` — notificationId, channel, deliveredAt
- `NotificationFailed` — notificationId, channel, reason

## Test Criteria

1. Order confirmed → PUSH sent; if no device token → fallback to SMS
2. 11th PUSH in 1 hour → rate limited, deferred
3. Two events for same order within 5min → merged into single notification
4. Template renders with correct variables
5. Failed SMS → fallback to EMAIL

## Files to Modify

- `src/main/java/.../notification/domain/` — channels, templates, rate limiter
- `src/main/java/.../notification/application/`
- `src/main/java/.../notification/adapters/messaging/` — event handlers
- `migrations/`
